package com.poc.sidecar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuracao externalizada do sidecar. O sidecar nao conhece mais, em
 * codigo, nem o backend, nem os paths que protege, nem a politica de escopo
 * por verbo: tudo isso e uma lista de rotas ({@link Route}), cada uma
 * roteando um prefixo de path para um backend com sua propria politica de
 * escopo. Isso permite que UM sidecar sirva varios componentes (ex.:
 * crud-service e billing-service) e que o mesmo jar/imagem seja reaproveitado
 * para topologias totalmente diferentes apenas trocando o application.yml
 * (ou variaveis de ambiente) - sem recompilar.
 *
 * Cada rota tambem aponta para um client-registration OAuth2 proprio
 * ({@code client-registration-id}, correspondendo a um client-id distinto no
 * Keycloak): cada microservico tem seu proprio client e seus proprios
 * escopos (ex.: "task:read"/"task:write" para o crud-service, "billing:read"/
 * "billing:write" para o billing-service), em vez de todo mundo compartilhar
 * um unico client/escopo genericos "read"/"write".
 *
 * Exemplo (application.yml):
 *
 * sidecar:
 *   routes:
 *     - path: /api/tasks
 *       backend-base-url: http://crud-service:8081
 *       client-registration-id: tasks-client
 *       scopes:
 *         default-scope: task:write
 *         mapping:
 *           GET: task:read
 *           HEAD: task:read
 *           POST: task:write
 *           PUT: task:write
 *           PATCH: task:write
 *           DELETE: task:write
 *     - path: /api/billing
 *       backend-base-url: http://billing-service:8083
 *       client-registration-id: billing-client
 *       scopes:
 *         default-scope: billing:write
 *         mapping:
 *           GET: billing:read
 *           HEAD: billing:read
 *           POST: billing:write
 *           PUT: billing:write
 *           PATCH: billing:write
 *           DELETE: billing:write
 *
 * Uma rota isolada tambem pode ser sobrescrita via variavel de ambiente
 * (relaxed binding com indice da lista), ex.:
 *   SIDECAR_ROUTES_1_BACKENDBASEURL=http://billing-service:9090
 *   SIDECAR_ROUTES_1_SCOPES_MAPPING_GET=billing:read
 */
@ConfigurationProperties(prefix = "sidecar")
public class SidecarProperties {

    /**
     * Tabela de roteamento: cada entrada e um path protegido, o backend para
     * o qual ele e encaminhado e a politica de escopo por verbo HTTP daquele
     * path. A ordem nao importa para o casamento (o controller escolhe a
     * rota mais especifica), mas a primeira rota cujo path bate com a
     * requisicao "ganha" em caso de empate de especificidade.
     */
    private List<Route> routes = new ArrayList<>();

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    public static class Route {

        /**
         * Prefixo de path protegido e exposto publicamente pelo sidecar,
         * ex.: "/api/tasks". Casa com o proprio path e com qualquer
         * subpath ("/api/tasks/1").
         */
        private String path;

        /**
         * URL base do backend para o qual as chamadas que casam com "path"
         * sao encaminhadas.
         */
        private String backendBaseUrl;

        /**
         * Id do client OAuth2 (em {@code spring.security.oauth2.client.registration})
         * usado para autenticar/autorizar chamadas a esta rota. Cada
         * microservico tem o seu proprio (ex.: "tasks-client", "billing-client"),
         * cada um mapeando para um client-id diferente no Keycloak.
         */
        private String clientRegistrationId;

        private final Scopes scopes = new Scopes();

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getBackendBaseUrl() {
            return backendBaseUrl;
        }

        public void setBackendBaseUrl(String backendBaseUrl) {
            this.backendBaseUrl = backendBaseUrl;
        }

        public String getClientRegistrationId() {
            return clientRegistrationId;
        }

        public void setClientRegistrationId(String clientRegistrationId) {
            this.clientRegistrationId = clientRegistrationId;
        }

        public Scopes getScopes() {
            return scopes;
        }

        /**
         * Casa o path protegido desta rota com o path de uma requisicao: o
         * proprio path, ou qualquer coisa abaixo dele (ex.: "/api/tasks"
         * casa "/api/tasks" e "/api/tasks/1").
         */
        public boolean matches(String requestUri) {
            return requestUri.equals(path) || requestUri.startsWith(path + "/");
        }
    }

    public static class Scopes {

        /**
         * Mapa metodo HTTP -> escopo exigido (ex.: GET -> read). A busca por
         * chave e case-insensitive, entao "get", "Get" ou "GET" funcionam.
         */
        private Map<String, String> mapping = new LinkedHashMap<>();

        /**
         * Escopo exigido quando o metodo HTTP da requisicao nao esta presente
         * no mapa acima.
         */
        private String defaultScope = "write";

        public Map<String, String> getMapping() {
            return mapping;
        }

        public void setMapping(Map<String, String> mapping) {
            this.mapping = mapping;
        }

        public String getDefaultScope() {
            return defaultScope;
        }

        public void setDefaultScope(String defaultScope) {
            this.defaultScope = defaultScope;
        }
    }
}
