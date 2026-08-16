package com.poc.sidecar.proxy;

import com.poc.sidecar.config.SidecarProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unico ponto de entrada exposto para o mundo externo.
 *
 * Nem os paths protegidos, nem os backends para os quais eles sao
 * encaminhados, nem o client OAuth2 (client-id) usado, nem a politica de
 * escopo por metodo HTTP estao no codigo: tudo vem de {@link SidecarProperties}
 * (propriedade {@code sidecar.routes}). Isso permite que um unico sidecar
 * sirva varios componentes ao mesmo tempo (ex.: crud-service em "/api/tasks"
 * autenticado via "tasks-client"/escopos "task:*", billing-service em
 * "/api/billing" via "billing-client"/escopos "billing:*"), cada um com seu
 * proprio client Keycloak e sua propria politica - e que novas rotas sejam
 * adicionadas so editando configuracao, sem recompilar.
 *
 * Como cada rota pode usar um client OAuth2 diferente, o authorized client
 * nao da mais para resolver com {@code @RegisteredOAuth2AuthorizedClient}
 * (que exige um registration-id fixo em tempo de compilacao): pedimos na mao,
 * ao {@link OAuth2AuthorizedClientManager}, pelo registration-id da rota
 * casada - o que, de brinde, tambem da refresh automatico de token expirado
 * via refresh_token (ver o bean em SecurityConfig), coisa que buscar direto
 * no OAuth2AuthorizedClientRepository nao fazia.
 *
 * Se o usuario nao esta autenticado com NENHUM client ainda, o
 * RouteAwareAuthenticationEntryPoint ja cuida disso (redireciona para o
 * client certo antes de chegar aqui). Se o usuario ja esta autenticado mas
 * nunca autorizou o client desta rota especifica (ex.: e a primeira vez que
 * acessa "/api/billing" numa sessao que so tinha aberto "/api/tasks"), ou
 * autorizou mas o token nao tem o escopo necessario, este controller
 * (re)inicia o processo de OAuth para aquele client - pedindo consentimento
 * adicional (step-up) quando o client ja existe, em vez de simplesmente
 * barrar com 403.
 */
@RestController
@CrossOrigin(origins = "*")
public class ProxyController {

    /**
     * Uma rota configurada, ja com seu RestClient pronto (evita recriar a
     * cada requisicao). Ordenada da mais especifica para a menos especifica,
     * para que um path mais longo (ex.: "/api/tasks/legacy") ganhe de um
     * prefixo mais curto (ex.: "/api/tasks") em caso de sobreposicao.
     */
    private record ResolvedRoute(SidecarProperties.Route config, RestClient client) {
    }

    private final List<ResolvedRoute> routes;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    // Sem isso, apos autorizar um client pela primeira vez (ou fazer step-up)
    // o usuario cai na home ("/") em vez de voltar pro path que ele pediu -
    // o request cache e o que faz o Spring "lembrar" pra onde reenviar depois
    // do redirect ao Keycloak (mesmo mecanismo que o entry point ja ganha de
    // graca quando quem intercepta e o proprio Spring Security).
    private final RequestCache requestCache = new HttpSessionRequestCache();

    public ProxyController(SidecarProperties properties,
                            OAuth2AuthorizedClientManager authorizedClientManager,
                            RestClient.Builder restClientBuilder) {
        this.authorizedClientManager = authorizedClientManager;
        // "restClientBuilder" e o Builder auto-configurado pelo Spring Boot: e
        // ele quem aplica spring.http.client.connect-timeout/read-timeout.
        // RestClient.create(url) (usado antes) ignora essa configuracao por
        // completo - os timeouts no application.yml nao tinham efeito nenhum.
        // .clone() por rota porque .baseUrl() muda o builder e ele nao e
        // thread-safe pra reuso concorrente sem clonar; cada RestClient e
        // criado uma unica vez aqui (nao a cada requisicao) e reaproveitado -
        // RestClient em si e imutavel/thread-safe apos build().
        this.routes = properties.getRoutes().stream()
                .sorted(Comparator.comparingInt((SidecarProperties.Route r) -> r.getPath().length()).reversed())
                .map(r -> new ResolvedRoute(r, restClientBuilder.clone().baseUrl(r.getBackendBaseUrl()).build()))
                .toList();
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                         HttpServletResponse response,
                                         @RequestBody(required = false) byte[] body,
                                         Authentication authentication,
                                         @AuthenticationPrincipal OidcUser oidcUser) throws IOException {

        String requestUri = request.getRequestURI();
        ResolvedRoute route = resolveRoute(requestUri);
        if (route == null) {
            // Nenhuma rota configurada casa com esse path: nao ha backend para
            // encaminhar. Diferente de escopo insuficiente, aqui nao existe
            // "consentimento adicional" que resolva - e 404 mesmo.
            return ResponseEntity.notFound().build();
        }

        String registrationId = route.config().getClientRegistrationId();
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                .principal(authentication)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();
        // Nunca autorizado ainda -> null (igual antes). Ja autorizado mas
        // expirado e com refresh_token valido -> renova aqui mesmo (chamada
        // ao Keycloak), sem exigir novo login interativo do usuario.
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);

        if (authorizedClient == null) {
            // Sessao ja autenticada (por outro client/rota), mas o client desta
            // rota nunca foi autorizado - primeiro acesso a este microservico
            // nesta sessao. Login "frio" para ESTE client, sem forcar consent:
            // ele so vai pedir o escopo default (silencioso) configurado para
            // ele (ex.: "task:read"), igual a um login normal.
            saveRequestIfReplayable(request, response);
            response.sendRedirect("/oauth2/authorization/" + registrationId);
            return null;
        }

        String httpMethod = request.getMethod();
        String requiredScope = requiredScopeFor(route.config(), httpMethod);

        Set<String> grantedScopes = authorizedClient.getAccessToken().getScopes();

        if (!grantedScopes.contains(requiredScope)) {
            // Client ja autorizado, mas sem o escopo necessario: (re)inicia o
            // fluxo OAuth PARA ESSE MESMO CLIENT solicitando exatamente o
            // escopo que falta (via "scope=") - assim o Keycloak so exibe/pede
            // consentimento por ele agora, no step-up, nao de antemao. O
            // StepUpAuthorizationRequestResolver adiciona esse escopo ao
            // pedido e "prompt=consent" para o Keycloak reabrir a tela de
            // consentimento mesmo que ja exista sessao/consentimento anterior.
            saveRequestIfReplayable(request, response);
            String scopeParam = URLEncoder.encode(requiredScope, StandardCharsets.UTF_8);
            response.sendRedirect("/oauth2/authorization/" + registrationId + "?reauth=true&scope=" + scopeParam);
            return null;
        }

        String downstreamPath = requestUri.replaceFirst("^/api", "");
        String queryString = request.getQueryString();
        String fullPath = queryString != null ? downstreamPath + "?" + queryString : downstreamPath;

        return route.client().method(HttpMethod.valueOf(httpMethod))
                .uri(fullPath)
                .headers(headers -> {
                    // Propaga identidade para o backend, mas nunca o token bruto:
                    // os backends confiam no sidecar, nao precisam validar JWT.
                    headers.set("X-Auth-User", oidcUser.getPreferredUsername());
                    headers.set("X-Auth-Scopes", String.join(",", grantedScopes));
                    if (body != null && body.length > 0) {
                        headers.setContentType(MediaType.APPLICATION_JSON);
                    }
                })
                .body(body != null ? body : new byte[0])
                .retrieve()
                .toEntity(byte[].class);
    }

    /**
     * Guarda a requisicao atual para o Spring reenviar o usuario de volta pra
     * ca depois do (re)inicio de OAuth - MAS so para GET/HEAD. O
     * SavedRequestAwareWrapper do Spring Security sobrescreve nao so a URL
     * como tambem o METODO e os HEADERS da requisicao "repetida" com os da
     * requisicao salva - ou seja, salvar um POST/PUT faria um GET seguinte
     * (o navegador so sabe fazer GET ao seguir um redirect 3xx) ser
     * processado aqui como se fosse aquele POST/PUT original, com um corpo
     * vazio mas o Content-Type antigo, e o proxy encaminharia isso pro
     * backend com o verbo errado (foi assim que um 415 apareceu num GET
     * durante os testes). Para metodos com corpo, a UX aceita nesta PoC e
     * cair na home apos o (re)inicio de OAuth e o usuario repetir a chamada.
     */
    private void saveRequestIfReplayable(HttpServletRequest request, HttpServletResponse response) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            requestCache.saveRequest(request, response);
        }
    }

    /**
     * Encontra a rota configurada cujo path protegido casa com a requisicao
     * (o proprio path, ou qualquer coisa abaixo dele). {@code routes} ja esta
     * ordenada da mais especifica para a menos especifica.
     */
    private ResolvedRoute resolveRoute(String requestUri) {
        return routes.stream()
                .filter(r -> r.config().matches(requestUri))
                .findFirst()
                .orElse(null);
    }

    /**
     * Metodo HTTP -> escopo exigido para a rota casada, externalizado via
     * {@link SidecarProperties} (propriedade {@code sidecar.routes[].scopes}).
     */
    private String requiredScopeFor(SidecarProperties.Route route, String httpMethod) {
        SidecarProperties.Scopes scopes = route.getScopes();
        return scopes.getMapping().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(httpMethod))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(scopes.getDefaultScope());
    }
}
