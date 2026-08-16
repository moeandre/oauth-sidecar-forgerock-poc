# PoC: Sidecar de OAuth2/OIDC na frente de múltiplos backends

Objetivo: provar que dá para tirar toda a responsabilidade de autenticação/
autorização dos microserviços de negócio e concentrá-la em um **sidecar**
único, que roteia por path para N backends diferentes, que:

1. Intercepta **todas** as chamadas, para todos os backends configurados.
2. Cada microserviço tem seu **próprio client-id no ForgeRock AM** e seus
   **próprios escopos** (ex.: `task:read`/`task:write` para o `crud-service`,
   `billing:read`/`billing:write` para o `billing-service`) — não existe um
   escopo genérico `read`/`write` compartilhado entre tudo.
3. Para `GET`/`HEAD` exige o escopo `<recurso>:read` daquele client no access
   token; para `POST`/`PUT`/`PATCH`/`DELETE`, `<recurso>:write`.
4. Se o usuário não está autenticado → inicia o Authorization Code Flow com o
   client do microserviço correspondente ao path acessado (redireciona ao
   AM).
5. Se está autenticado mas nunca autorizou aquele client (primeiro acesso a
   um microserviço na sessão), ou autorizou mas falta o escopo necessário →
   **reinicia** o fluxo OAuth para aquele client, pedindo consentimento
   adicional quando for o caso (step-up), em vez de simplesmente devolver 403.

Nem o path protegido, nem o backend de destino, nem o client OAuth2 usado,
nem a política de escopo por verbo estão em código: são uma **tabela de
rotas** (`sidecar.routes` em
[`oauth-sidecar/.../application.yml`](oauth-sidecar/src/main/resources/application.yml)).
Isso é o que permite o mesmo sidecar proteger dois componentes de negócio
completamente diferentes (`crud-service` e `billing-service`), cada um com
seu próprio client no AM, ao mesmo tempo.

```
                                   ┌──► crud-service    (8081, não exposto) — /api/tasks
Browser/Client ──► oauth-sidecar ──┤
                    (8082)         └──► billing-service (8083, não exposto) — /api/billing
                         │
                         └──► ForgeRock AM (8080) — login / emissão de token
```

Nem `crud-service` nem `billing-service` conhecem OAuth: ambos só existem na
rede interna do docker-compose e nunca são publicados no host. Essa é a
demonstração central do padrão sidecar — e nenhuma linha desses dois serviços
foi tocada nesta migração (ver seção 0 abaixo).

## 0. Sobre esta migração (Keycloak → ForgeRock AM)

Esta PoC usava Keycloak originalmente; agora usa o **ForgeRock Access
Management**, via a imagem `openidentityplatform/openam` (o fork open-source
que deu origem ao produto comercial da Ping/ForgeRock — não é a imagem
oficial licenciada da Ping, que não tem distribuição pública gratuita para
`docker compose up`). `billing-service` e `crud-service` não sofreram
nenhuma alteração: o sidecar continua sendo o único componente que fala
OAuth2/OIDC, e do ponto de vista do Spring Security o provedor é só mais um
`ClientRegistration`/`issuer-uri` — a troca de IdP não vazou para os
microserviços de negócio.

Diferente do Keycloak (que sobe já configurado via `start-dev
--import-realm` + um único JSON), o AM não tem um mecanismo de import
declarativo pronto para uso via `docker compose up`. O provisionamento aqui
é feito em duas etapas, ambas automatizadas (ver `forgerock/`):

1. **`forgerock/openam-entrypoint.sh`** — roda dentro do próprio container
   `openam`: na primeira subida, executa a instalação silenciosa
   (`openam-configurator-tool` + `forgerock/openam-config.properties`, que
   sobe com um OpenDJ embutido, sem container separado); nas subidas
   seguintes (config já persistida no volume `openam-data`), pula direto
   para o Tomcat.
2. **`forgerock/provision.sh`** — roda num container descartável
   (`openam-provision`) só depois que o `openam` fica saudável. Faz via
   REST o que o `keycloak/realm-export.json` fazia via import: cria o
   OAuth2 Provider com os 4 escopos, os clients `tasks-client`/
   `billing-client` e o usuário `demo`. É o equivalente ao antigo
   `realm-export.json`, só que como script em vez de um JSON estático —
   idempotente, pode rodar de novo sem quebrar nada.

## 1. Pré-requisito: hostname `openam`

Para o AM funcionar igual tanto para o navegador (rodando no seu host)
quanto para os containers (rede interna do Docker), os dois lados precisam
enxergar o AM pelo **mesmo** hostname: `openam`.

Adicione uma entrada no seu arquivo de hosts apontando para `127.0.0.1`:

- Linux/Mac: `/etc/hosts`
- Windows: `C:\Windows\System32\drivers\etc\hosts`

```
127.0.0.1 openam
```

Sem isso, o navegador não conseguirá resolver `http://openam:8080/...`
durante o redirect de login.

## 2. Subir o ambiente

```bash
cd oauth-sidecar-poc
docker compose up --build
```

A primeira subida demora mais que um `docker compose up` comum: o container
`openam` roda uma instalação silenciosa completa (Tomcat + OpenDJ embutido +
configuração inicial), o que leva cerca de 1 minuto; só depois disso o
healthcheck fica `healthy`, o `openam-provision` roda (alguns segundos) e aí
sim o `oauth-sidecar` sobe. Subidas seguintes são rápidas (a config do AM
persiste no volume `openam-data`).

Usuário de teste já provisionado:

- **username:** `demo`
- **senha:** `demo1234` (o AM exige mínimo de 8 caracteres — por isso não é
  `demo123` como era no Keycloak)

## 3. Testando o fluxo

Abra o navegador (importante: usar o navegador, pois o fluxo envolve
redirect + tela de login/consentimento do AM):

```
http://localhost:8082/api/tasks
```

- Você será redirecionado para o AM para logar como `demo`, pelo client
  `tasks-client` (é o que a rota `/api/tasks` usa — ver `sidecar.routes`).
- No primeiro login, o AM pede consentimento explícito para o escopo
  `task:read` (diferente do Keycloak original, que tinha um jeito de marcar
  um escopo como "não exibir na tela de consentimento" — o AM não tem esse
  controle granular por escopo pronto de fábrica nesta versão). Aprovando
  uma vez, o AM lembra o consentimento e não pede de novo em logins
  seguintes — só o step-up abaixo força a tela a reaparecer.
- `task:write` **não é pedido no login** — só aparece quando necessário (ver
  passo a passo abaixo).

Agora tente uma escrita, por exemplo via um formulário/REST client
autenticado na mesma sessão do navegador, ou simplesmente acesse uma rota
de escrita — como o teste de POST/PUT precisa de corpo, use uma extensão
tipo "Requestly"/Postman com a sessão do navegador, ou curl com a cookie
de sessão copiada. Para simplificar a demo, a forma mais direta é:

1. Tente `PUT http://localhost:8082/api/tasks/1` (o token atual só tem `task:read`).
2. O sidecar detecta que falta `task:write` e responde com um **redirect
   para `/oauth2/authorization/tasks-client?reauth=true&scope=task:write`**.
3. Isso reabre a tela de consentimento do AM (`prompt=consent`), agora
   incluindo o escopo `task:write`.
4. Após aprovar, repita a chamada — agora ela é encaminhada ao
   `crud-service` normalmente.

Acessar `/api/billing` pela primeira vez na mesma sessão do navegador dispara
um fluxo equivalente, mas para o client `billing-client` (escopos
`billing:read`/`billing:write`) — são clients e tokens independentes, mesmo
sendo o mesmo usuário `demo` autenticado nos dois.

Endpoints, todos por trás de `/api` e roteados pelo sidecar conforme
`sidecar.routes` (ver seção 4):

**`/api/tasks` → `crud-service`, client `tasks-client`**

| Método | Rota              | Escopo exigido |
|--------|-------------------|----------------|
| GET    | `/api/tasks`      | task:read      |
| GET    | `/api/tasks/{id}` | task:read      |
| POST   | `/api/tasks`      | task:write     |
| PUT    | `/api/tasks/{id}` | task:write     |
| DELETE | `/api/tasks/{id}` | task:write     |

Corpo para POST/PUT: `{ "title": "Estudar sidecar OAuth", "description": "PoC", "done": false }`

**`/api/billing` → `billing-service`, client `billing-client`**

| Método | Rota                | Escopo exigido |
|--------|---------------------|----------------|
| GET    | `/api/billing`      | billing:read   |
| GET    | `/api/billing/{id}` | billing:read   |
| POST   | `/api/billing`      | billing:write  |
| PUT    | `/api/billing/{id}` | billing:write  |
| DELETE | `/api/billing/{id}` | billing:write  |

Corpo para POST/PUT: `{ "description": "Assinatura mensal", "amount": 99.90, "paid": false }`

Qualquer path sob `/api` que não bata com nenhuma rota configurada responde
`404` (depois de autenticar — a autenticação é exigida para toda a aplicação,
independente de existir rota para o path).

## 4. Onde olhar o código

- `oauth-sidecar/.../config/SecurityConfig.java` — exige login OAuth2 em
  toda rota (exceto `/actuator/health`).
- `oauth-sidecar/.../config/RouteAwareAuthenticationEntryPoint.java` — com
  múltiplos clients (um por microserviço), o Spring Security não sabe
  sozinho para qual redirecionar um usuário não autenticado; esta classe
  olha o path pedido, acha a rota correspondente em `sidecar.routes` e
  redireciona direto para o client daquele microserviço, em vez da página
  genérica "Login with OAuth 2.0" (que lista um link por client).
- `oauth-sidecar/.../config/StepUpAuthorizationRequestResolver.java` —
  injeta `prompt=consent` e o escopo faltante quando pedimos reautorização
  para um client já autorizado (step-up).
- `oauth-sidecar/.../config/SidecarProperties.java` — a tabela de roteamento
  externalizada (`sidecar.routes`): path protegido, URL do backend, o
  client-id OAuth2 (`client-registration-id`) e a política de escopo por
  verbo HTTP de cada rota. Nada disso é código; é só `application.yml` (ou
  variável de ambiente). Adicionar um novo componente atrás do sidecar é
  acrescentar uma entrada aqui, o client correspondente em
  `spring.security.oauth2.client.registration` e o client no AM (ver
  `forgerock/provision.sh`) — sem recompilar nada.
- `oauth-sidecar/.../proxy/ProxyController.java` — é o **interceptor**:
  resolve qual rota configurada bate com o path da requisição, busca o
  `OAuth2AuthorizedClient` do client daquela rota (via
  `OAuth2AuthorizedClientRepository`, já que o client varia por rota e não
  dá mais para fixar via `@RegisteredOAuth2AuthorizedClient`), decide o
  escopo exigido por método HTTP, valida contra o access token e faz o
  proxy para o backend correspondente, ou dispara o (re)início de OAuth
  para aquele client. Path sem rota configurada → `404`.
- `crud-service/.../controller/TaskController.java` e
  `billing-service/.../controller/BillingController.java` — CRUD puro, sem
  nenhuma linha de código de segurança; cada um só existe na rede interna
  do compose. **Não modificados nesta migração.**
- `forgerock/openam-config.properties` + `forgerock/openam-entrypoint.sh` —
  instalação silenciosa do AM (equivalente ao `start-dev --import-realm` do
  Keycloak, mas em duas peças porque este fork não tem um "modo dev" com
  import automático pronto).
- `forgerock/provision.sh` — equivalente ao antigo `keycloak/realm-export.json`:
  cria via REST o OAuth2 Provider (com os 4 escopos `<recurso>:read`/
  `<recurso>:write`), os clients `tasks-client`/`billing-client` e o usuário
  `demo`. Os comentários no topo do arquivo documentam decisões
  não-óbvias específicas deste fork do AM (por que tudo fica no realm raiz,
  o formato exigido para atributos multivalorados do client OAuth2, etc.).

## 5. Simplificações desta PoC (documentadas de propósito)

- `H2` em memória no `crud-service` e no `billing-service` — dados somem a cada restart.
- CSRF desabilitado no sidecar só para facilitar testes com curl/Postman.
- Tudo provisionado no **realm raiz** (`/`) do AM, não num realm dedicado:
  neste fork, um realm criado via REST não vem com suporte a identidades do
  tipo "Agent" (necessário para os OAuth2 Clients) — só o realm raiz tem
  isso de fábrica num install silencioso. Ver comentários no topo de
  `forgerock/provision.sh`.
- PKCE (`code_challenge`) desligado no OAuth2 Provider — o
  `spring-boot-starter-oauth2-client` não envia `code_challenge` por padrão
  para clients confidenciais (com client-secret), só para clients públicos.
- Sem tela de consentimento "silenciosa" por escopo: o AM (nesta versão)
  não tem o equivalente direto ao `display.on.consent.screen: false` do
  Keycloak, então o primeiro login já mostra consentimento para `task:read`/
  `billing:read` (não só para o `:write`, como acontecia no Keycloak).
- `ADMIN_PWD`/senhas de dev em texto plano em `forgerock/openam-config.properties`
  e no `docker-compose.yml` — só para ambiente local, nunca faça isso fora
  disso (mesmo espírito do `sslRequired: none` que já existia na versão
  Keycloak desta PoC).
- O token de acesso não é repassado ao `crud-service` (ele não teria como
  validar); em vez disso propagamos identidade via headers
  `X-Auth-User` / `X-Auth-Scopes` — um passo further seria assinar/cifrar
  esses headers ou usar mTLS entre sidecar e serviço.
- Sem refresh automático de token expirado nesta versão — para produção,
  o `spring-boot-starter-oauth2-client` já dá suporte a isso via
  `OAuth2AuthorizedClientManager` com `refresh_token`.
