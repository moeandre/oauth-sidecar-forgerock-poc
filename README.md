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
                         └──► ForgeRock AM / PingAM (sua instância) — login / emissão de token
```

Nem `crud-service` nem `billing-service` conhecem OAuth: ambos só existem na
rede interna do docker-compose e nunca são publicados no host. Essa é a
demonstração central do padrão sidecar — e nenhuma linha desses dois serviços
foi tocada nesta migração.

## 0. Sobre esta migração (Keycloak → ForgeRock AM) e por que não há um AM no docker-compose

Esta PoC usava Keycloak originalmente; agora usa **ForgeRock Access
Management / PingAM**. `billing-service` e `crud-service` não sofreram
nenhuma alteração: o sidecar continua sendo o único componente que fala
OAuth2/OIDC, e do ponto de vista do Spring Security o provedor é só mais um
`ClientRegistration`/`issuer-uri` — a troca de IdP não vazou para os
microserviços de negócio.

Diferente do Keycloak (que tinha uma imagem Docker oficial leve e subia já
configurado via `--import-realm`), esta PoC **não sobe um AM/PingAM
próprio no `docker compose up`** — ela espera uma instância já existente
(sua, da sua organização, ou um tenant PingAM/Identity Cloud) e só aponta o
sidecar para ela via variável de ambiente. Isso foi uma decisão deliberada,
não só simplicidade: a imagem Docker community mais próxima de um "Keycloak
dev-mode" (`openidentityplatform/openam`) foi testada extensivamente aqui e
apresenta um bug de emissão de **ID Token OIDC** (a troca do `code` por token
retorna 500 sempre que o escopo `openid` está presente — confirmado em duas
versões da imagem, `16.1.2` e `16.0.6`, sem stack trace acessível em nenhum
log). Como o sidecar depende de OIDC (não só OAuth2 puro — usa
`OidcUser.getPreferredUsername()` e o `id_token` para logout), não dava para
contornar isso só com configuração. Ver histórico de investigação no final
desta seção se for útil para depurar aquele fork depois.

## 1. Pré-requisito: o que precisa existir no seu ForgeRock AM/PingAM

Antes de subir o `docker compose`, seu realm no AM precisa ter:

1. **Um OAuth2/OIDC Provider configurado** no realm, com os 4 escopos usados
   por esta PoC declarados (`task:read`, `task:write`, `billing:read`,
   `billing:write`) — em **Realms > [seu realm] > Services > OAuth2
   Provider**, seção de escopos suportados.
2. **Dois OAuth2 Clients**, um por microserviço:

   | Campo | `tasks-client` | `billing-client` |
   |---|---|---|
   | Client ID | `tasks-client` | `billing-client` |
   | Client Secret | defina o seu | defina o seu |
   | Client Type | Confidential | Confidential |
   | Grant Types | Authorization Code (+ Refresh Token) | Authorization Code (+ Refresh Token) |
   | Redirect URI | `http://localhost:8082/login/oauth2/code/tasks-client` | `http://localhost:8082/login/oauth2/code/billing-client` |
   | Scopes | `openid`, `task:read`, `task:write` | `openid`, `billing:read`, `billing:write` |
   | Default/pre-consentido | `openid`, `task:read` | `openid`, `billing:read` |
   | Token Endpoint Auth Method | `client_secret_basic` | `client_secret_basic` |

   O redirect URI precisa ser **exato** (sem coringa) — é o padrão
   `{baseUrl}/login/oauth2/code/{registrationId}` do Spring Security (ver
   `oauth-sidecar/.../application.yml`).
3. **Um usuário de teste** (ex.: `demo`) com senha que atenda a política de
   senha do seu AM.
4. Se o seu AM pedir consentimento explícito na tela de login (em vez de
   conceder `*-read` silenciosamente, como o Keycloak original fazia): tudo
   bem, é só aprovar uma vez — o comportamento de step-up (pedir `*-write`
   só quando necessário, via `prompt=consent`) funciona de qualquer forma.

Anote a **issuer URI** do seu realm (o endereço a partir do qual
`/.well-known/openid-configuration` resolve) — você vai precisar dela no
próximo passo.

## 2. Subir o ambiente

Defina as 3 variáveis de ambiente que o `docker-compose.yml` exige (o
compose falha rápido, com mensagem clara, se alguma estiver faltando):

```bash
export FORGEROCK_ISSUER_URI="https://SEU-AM/am/oauth2/realms/root/realms/SEU-REALM"
export TASKS_CLIENT_SECRET="o-secret-do-tasks-client"
export BILLING_CLIENT_SECRET="o-secret-do-billing-client"

cd oauth-sidecar-poc
docker compose up --build
```

(No Windows/PowerShell: `$env:FORGEROCK_ISSUER_URI = "..."` etc., ou crie um
`.env` na raiz do projeto com essas 3 linhas — o `docker compose` lê
automaticamente.)

Se o `client-id` do seu client não for literalmente `tasks-client`/
`billing-client`, sobrescreva também `TASKS_CLIENT_ID`/`BILLING_CLIENT_ID`.

## 3. Testando o fluxo

Abra o navegador (importante: usar o navegador, pois o fluxo envolve
redirect + tela de login/consentimento do AM):

```
http://localhost:8082/api/tasks
```

- Você será redirecionado para o AM para logar como seu usuário de teste,
  pelo client `tasks-client` (é o que a rota `/api/tasks` usa — ver
  `sidecar.routes`).
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
sendo o mesmo usuário autenticado nos dois.

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
  `spring.security.oauth2.client.registration` e o client no seu AM (ver
  seção 1) — sem recompilar nada.
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

## 5. Simplificações desta PoC (documentadas de propósito)

- `H2` em memória no `crud-service` e no `billing-service` — dados somem a cada restart.
- CSRF desabilitado no sidecar só para facilitar testes com curl/Postman.
- O token de acesso não é repassado ao `crud-service` (ele não teria como
  validar); em vez disso propagamos identidade via headers
  `X-Auth-User` / `X-Auth-Scopes` — um passo further seria assinar/cifrar
  esses headers ou usar mTLS entre sidecar e serviço.
- Sem refresh automático de token expirado nesta versão — para produção,
  o `spring-boot-starter-oauth2-client` já dá suporte a isso via
  `OAuth2AuthorizedClientManager` com `refresh_token`.
- Sem AM próprio no compose (ver seção 0) — dependência de uma instância
  externa é uma simplificação real desta PoC, não só estética: reduz o que
  precisa ser validado/mantido aqui, mas também significa que "subir o
  ambiente" não é mais um único `docker compose up` autocontido como era
  com o Keycloak.

## 6. Histórico da investigação do fork `openidentityplatform/openam`

Documentado aqui só para quem for tentar novamente rodar um AM self-hosted
via Docker nesta PoC no futuro — não é necessário para usar o que está
implementado hoje (seção 1-3).

Chegamos a implementar e validar um `docker-compose` completo com
`openidentityplatform/openam` (fork open-source que deu origem ao produto
comercial), incluindo:

- Instalação silenciosa automatizada (Tomcat + OpenDJ embutido).
- Provisionamento via REST do OAuth2 Provider, dos dois clients e do
  usuário de teste (equivalente ao `realm-export.json` do Keycloak).
- Um bug real e corrigido: o AM tenta gravar o consentimento do usuário
  ("Allow") num atributo LDAP configurável (`savedConsentAttribute`) que
  vem **vazio** por padrão num install silencioso — tentar gravar nele
  quebra com HTTP 500 (`Illegal arguments: One or more required arguments
  is null or empty`, visível em
  `/usr/openam/config/openam/debug/OAuth2Provider`). Corrigido estendendo o
  schema LDAP com um atributo próprio via `ldapmodify` e configurando
  `savedConsentAttribute` para apontar para ele.
- Um segundo bug, **não resolvido**: mesmo com o anterior corrigido, a
  troca do `code` por token (`POST /oauth2/access_token`) retorna 500
  sempre que o escopo `openid` está presente (ou seja, sempre que se pede
  um ID Token OIDC, não só um access token OAuth2 puro). Confirmado:
  - Reproduzível em duas versões da imagem (`16.1.2` e `16.0.6`).
  - Sem relação com PKCE (falha com e sem `code_challenge`).
  - Sem relação com os scripts padrão de modificação de token/claims
    (falha igual com os scripts Groovy padrão, com eles trocados por
    scripts JavaScript no-op, e com o campo de script vazio/`[Empty]`).
  - **Sem escopo `openid`** (OAuth2 puro), o mesmo fluxo completa com
    HTTP 200 normalmente — isolando o bug especificamente na emissão do
    ID Token (JWT), não no access/refresh token (que são opacos nesta
    config).
  - Nenhum stack trace foi encontrado em nenhum log acessível (debug
    files do AM, nem stdout/stderr do container) apesar de tentativas
    extensivas de elevar o nível de log.

  Próximos passos possíveis para quem retomar isso: tentar um datastore
  externo em vez do OpenDJ embutido, trocar o algoritmo de assinatura do
  ID Token, ou abrir uma issue no repositório
  [OpenIdentityPlatform/OpenAM](https://github.com/OpenIdentityPlatform/OpenAM)
  com esse reprodutor.
