#!/bin/bash
# Entrypoint do container "openam" (ver docker-compose.yml).
#
# A imagem openidentityplatform/openam nao vem pre-configurada: na primeira
# vez que sobe, o webapp responde mas nao tem realm/admin/diretorio nenhum
# criado ainda - e preciso rodar o openam-configurator-tool (instalacao
# silenciosa, via forgerock/openam-config.properties) contra o Tomcat ja
# no ar. Esse passo so roda uma vez: ele grava um "boot.json" dentro do
# volume persistente (openam-data no compose); se ele ja existir, pulamos
# direto pra subir o Tomcat em foreground.
set -euo pipefail

BOOT_FILE="${OPENAM_DATA_DIR}/boot.json"
# Arquivo-marcador que o healthcheck do docker-compose usa para saber que a
# instalacao silenciosa terminou - ver comentario abaixo sobre por que o
# healthcheck NAO bate em /json/authenticate diretamente.
READY_FILE="${OPENAM_DATA_DIR}/.provisioning-ready"
CONFIGURATOR_JAR=$(ls /usr/openam/ssoconfiguratortools/openam-configurator-tool-*.jar | head -1)

if [ ! -f "${BOOT_FILE}" ]; then
    echo "[openam-entrypoint] Nenhuma configuracao encontrada em ${OPENAM_DATA_DIR} - rodando instalacao silenciosa..."

    # O configurador fala HTTP com o proprio Tomcat, entao ele precisa estar
    # de pe (mesmo com o webapp ainda "nao configurado") antes de rodarmos o
    # jar. "catalina.sh start" sobe em background e devolve o prompt.
    catalina.sh start

    echo "[openam-entrypoint] Aguardando o Tomcat/webapp OpenAM responder..."
    until curl -sf -o /dev/null "http://localhost:8080/openam/isAlive.jsp"; do
        sleep 2
    done

    java -jar "${CONFIGURATOR_JAR}" --file /tmp/openam-config.properties --acceptLicense

    echo "[openam-entrypoint] Instalacao concluida, reiniciando em foreground..."
    catalina.sh stop
    # Da tempo do Tomcat encerrar limpo antes de subir de novo em foreground.
    sleep 5
else
    echo "[openam-entrypoint] Configuracao existente encontrada em ${BOOT_FILE} - pulando instalacao."
fi

# Sinaliza para o healthcheck (ver docker-compose.yml) que a instalacao ja
# terminou. De proposito NAO usamos um healthcheck que bate em
# /json/authenticate: isso faria o docker rodar autenticacoes reais contra o
# AM a cada poucos segundos DURANTE a janela sensivel da instalacao silenciosa
# (que tambem esta de-obtendo/gravando o proprio admin token internamente) -
# na pratica, essa concorrencia foi o que causava falhas intermitentes de
# "Configuration Failed" (AdminTokenAction) na instalacao. O healthcheck so
# precisa saber "configuracao pronta + Tomcat respondendo", nao repetir login.
touch "${READY_FILE}"

exec catalina.sh run
