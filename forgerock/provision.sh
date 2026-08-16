#!/bin/sh
# Provisionamento via REST do OpenAM/ForgeRock AM para esta PoC - o
# equivalente ao antigo keycloak/realm-export.json, so que feito com
# chamadas REST em vez de um unico JSON importavel: este fork do AM nao tem
# um mecanismo de import declarativo (o Keycloak tem "--import-realm").
#
# Roda uma unica vez, num container descartavel (ver servico
# "openam-provision" no docker-compose.yml), depois que o healthcheck do
# "openam" confirma que a instalacao silenciosa (openam-entrypoint.sh) ja
# terminou. E seguro rodar mais de uma vez (idempotente): servico OAuth2 e
# clients sao "cria se nao existe, atualiza se existe"; o usuario demo tem a
# senha sempre resetada para o valor esperado.
#
# Decisoes que nao sao obvias e por isso estao documentadas aqui (ver
# tambem README, secao "Simplificacoes desta PoC"):
#
# 1. Tudo e criado no realm raiz ("/"), nao num realm dedicado tipo
#    "poc-realm": neste fork especifico do AM, um realm criado via REST
#    (POST /json/global-config/realms) nao vem com suporte a identidades do
#    tipo "Agent" (necessario para os OAuth2 Clients) - so o realm raiz tem
#    isso disponivel de fabrica num install silencioso. Criar um realm
#    filho com esse suporte exigiria passos adicionais (console classico/
#    amster, indisponiveis nesta imagem) fora do escopo de uma PoC.
# 2. Os atributos multivalorados do OAuth2 Client (redirectionURIs, scopes,
#    defaultScopes, responseTypes) precisam ser enviados no formato legado
#    "[indice]=valor" (ex.: "[0]=openid", "[1]=task:read"). Sem o prefixo
#    "[N]=", a chamada REST devolve 200 e ecoa os valores de volta
#    (parecendo ter funcionado), mas o endpoint /oauth2/authorize continua
#    rejeitando com "redirect_uri_mismatch" - o motor OAuth2 deste build
#    especifico so reconhece o valor nesse formato indexado. Descoberto
#    comparando um client criado manualmente via REST com um client de
#    controle criado pela pagina oficial /oauth2/registerClient.jsp.
# 3. "codeVerifierEnforced" (PKCE obrigatorio) e desligado no provider: o
#    spring-boot-starter-oauth2-client do sidecar nao envia code_challenge
#    para clients confidenciais (com client-secret) por padrao - com PKCE
#    obrigatorio, o /oauth2/authorize responde "invalid_request" pedindo
#    code_challenge.
# 4. Senha minima de usuario e 8 caracteres (o AM rejeita menor) - por isso
#    o usuario demo aqui usa "demo1234", nao "demo123" como no Keycloak.
set -eu

AM_BASE_URL="${AM_BASE_URL:-http://openam:8080/openam}"
ADMIN_PWD="${AM_ADMIN_PASSWORD:?AM_ADMIN_PASSWORD precisa estar definido}"
TASKS_CLIENT_SECRET="${TASKS_CLIENT_SECRET:?TASKS_CLIENT_SECRET precisa estar definido}"
BILLING_CLIENT_SECRET="${BILLING_CLIENT_SECRET:?BILLING_CLIENT_SECRET precisa estar definido}"
DEMO_USERNAME="${DEMO_USERNAME:-demo}"
DEMO_PASSWORD="${DEMO_PASSWORD:-demo1234}"
# Precisa bater com "{baseUrl}/login/oauth2/code/{registrationId}" do
# sidecar (ver oauth-sidecar/.../application.yml) - o AM exige match exato,
# sem coringa (diferente do "http://localhost:8082/*" que o Keycloak aceitava).
SIDECAR_BASE_URL="${SIDECAR_BASE_URL:-http://localhost:8082}"

log() { echo "[provision] $*"; }

# --- espera a API REST do AM responder autenticacao (o healthcheck do
#     "openam" no compose ja deveria garantir isso, mas nao custa reforcar) ---
log "Aguardando AM aceitar autenticacao de amadmin em ${AM_BASE_URL}..."
i=0
while true; do
    AUTH_RESPONSE=$(curl -sf -X POST "${AM_BASE_URL}/json/authenticate" \
        -H "Content-Type: application/json" \
        -H "X-OpenAM-Username: amadmin" \
        -H "X-OpenAM-Password: ${ADMIN_PWD}" \
        -H "Accept-API-Version: resource=2.0, protocol=1.0" 2>/dev/null || true)
    TOKEN=$(echo "${AUTH_RESPONSE}" | sed -n 's/.*"tokenId":"\([^"]*\)".*/\1/p')
    if [ -n "${TOKEN}" ]; then
        break
    fi
    i=$((i + 1))
    if [ "${i}" -ge 60 ]; then
        log "ERRO: AM nao aceitou autenticacao apos varias tentativas."
        exit 1
    fi
    sleep 3
done
log "Autenticado como amadmin."

auth_header="iplanetDirectoryPro: ${TOKEN}"

# --- OAuth2 Provider (realm raiz) com os 4 escopos por microservico ---
log "Configurando o OAuth2 Provider (escopos task:*/billing:*)..."
CREATE_HTTP=$(curl -s -o /tmp/oauth2-provider.json -w "%{http_code}" \
    -X POST "${AM_BASE_URL}/json/realms/root/realm-config/services/oauth-oidc?_action=create" \
    -H "Content-Type: application/json" -H "${auth_header}" -H "Accept-API-Version: resource=1.0" \
    -d '{
        "advancedOAuth2Config": {
            "supportedScopes": [
                "task:read|en|Ler tarefas",
                "task:write|en|Criar, editar e excluir tarefas",
                "billing:read|en|Ler faturas",
                "billing:write|en|Criar, editar e excluir faturas"
            ],
            "codeVerifierEnforced": false
        }
    }')
if [ "${CREATE_HTTP}" = "409" ]; then
    log "OAuth2 Provider ja existia - atualizando escopos/PKCE."
    curl -sf -X PUT "${AM_BASE_URL}/json/realms/root/realm-config/services/oauth-oidc" \
        -H "Content-Type: application/json" -H "${auth_header}" -H "Accept-API-Version: resource=1.0" \
        -d '{
            "advancedOAuth2Config": {
                "supportedScopes": [
                    "task:read|en|Ler tarefas",
                    "task:write|en|Criar, editar e excluir tarefas",
                    "billing:read|en|Ler faturas",
                    "billing:write|en|Criar, editar e excluir faturas"
                ],
                "codeVerifierEnforced": false
            }
        }' -o /dev/null
elif [ "${CREATE_HTTP}" != "200" ] && [ "${CREATE_HTTP}" != "201" ]; then
    log "ERRO: falha ao criar OAuth2 Provider (HTTP ${CREATE_HTTP}):"
    cat /tmp/oauth2-provider.json
    exit 1
fi

# --- OAuth2 Clients: um por microservico, cada um com seu proprio secret e
#     escopos (ver ponto 2 do cabecalho sobre o formato "[N]=valor") ---
create_or_update_client() {
    client_id="$1"
    client_secret="$2"
    resource_scope="$3"
    log "Configurando o client OAuth2 '${client_id}' (escopos ${resource_scope}:read/write)..."
    curl -sf -X PUT "${AM_BASE_URL}/json/realms/root/realm-config/agents/OAuth2Client/${client_id}" \
        -H "Content-Type: application/json" -H "${auth_header}" -H "Accept-API-Version: resource=1.0" \
        -d "{
            \"userpassword\": \"${client_secret}\",
            \"sunIdentityServerDeviceStatus\": \"Active\",
            \"com.forgerock.openam.oauth2provider.clientType\": \"Confidential\",
            \"com.forgerock.openam.oauth2provider.tokenEndPointAuthMethod\": \"client_secret_basic\",
            \"com.forgerock.openam.oauth2provider.redirectionURIs\": [\"[0]=${SIDECAR_BASE_URL}/login/oauth2/code/${client_id}\"],
            \"com.forgerock.openam.oauth2provider.responseTypes\": [\"[0]=code\"],
            \"com.forgerock.openam.oauth2provider.scopes\": [\"[0]=openid\", \"[1]=${resource_scope}:read\", \"[2]=${resource_scope}:write\"],
            \"com.forgerock.openam.oauth2provider.defaultScopes\": [\"[0]=openid\", \"[1]=${resource_scope}:read\"]
        }" -o /dev/null
}

create_or_update_client "tasks-client" "${TASKS_CLIENT_SECRET}" "task"
create_or_update_client "billing-client" "${BILLING_CLIENT_SECRET}" "billing"

# --- usuario de teste ---
log "Garantindo o usuario de teste '${DEMO_USERNAME}'..."
CREATE_USER_HTTP=$(curl -s -o /tmp/demo-user.json -w "%{http_code}" \
    -X POST "${AM_BASE_URL}/json/realms/root/users/?_action=create" \
    -H "Content-Type: application/json" -H "${auth_header}" -H "Accept-API-Version: protocol=2.1,resource=3.0" \
    -d "{\"username\":\"${DEMO_USERNAME}\",\"userPassword\":\"${DEMO_PASSWORD}\",\"mail\":\"${DEMO_USERNAME}@example.com\",\"cn\":\"${DEMO_USERNAME}\",\"sn\":\"${DEMO_USERNAME}\",\"givenName\":\"${DEMO_USERNAME}\"}")
if [ "${CREATE_USER_HTTP}" = "409" ]; then
    log "Usuario '${DEMO_USERNAME}' ja existia - garantindo a senha esperada."
    curl -sf -X PUT "${AM_BASE_URL}/json/realms/root/users/${DEMO_USERNAME}" \
        -H "Content-Type: application/json" -H "${auth_header}" -H "Accept-API-Version: protocol=2.1,resource=3.0" \
        -d "{\"userPassword\":\"${DEMO_PASSWORD}\"}" -o /dev/null
elif [ "${CREATE_USER_HTTP}" != "200" ] && [ "${CREATE_USER_HTTP}" != "201" ]; then
    log "ERRO: falha ao criar usuario demo (HTTP ${CREATE_USER_HTTP}):"
    cat /tmp/demo-user.json
    exit 1
fi

log "Provisionamento concluido: OAuth2 Provider, tasks-client, billing-client e usuario '${DEMO_USERNAME}' prontos."
