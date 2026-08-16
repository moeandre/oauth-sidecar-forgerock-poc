package com.poc.sidecar.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Com um client-id por microservico ({@link SidecarProperties.Route#getClientRegistrationId()}),
 * ha mais de um client OAuth2 registrado no sidecar - e sem essa classe, o
 * Spring Security nao saberia para qual redirecionar um usuario ainda nao
 * autenticado: o comportamento padrao do oauth2Login() com varios clients e
 * mostrar uma pagina generica "Login with OAuth 2.0" listando um link por
 * client, em vez de ir direto ao ponto.
 *
 * Aqui, descobrimos pelo path da requisicao qual rota (ver
 * {@code sidecar.routes}) o usuario esta tentando acessar e mandamos ele
 * logar direto pelo client daquela rota (ex.: "/api/tasks" ->
 * "/oauth2/authorization/tasks-client"). Path sem rota configurada cai no
 * client da primeira rota, so para dar algum ponto de entrada razoavel -
 * o ProxyController e quem decide de fato se o path existe (404) depois de
 * autenticado.
 */
public class RouteAwareAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SidecarProperties properties;

    public RouteAwareAuthenticationEntryPoint(SidecarProperties properties) {
        this.properties = properties;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        String registrationId = resolveRegistrationId(request.getRequestURI());
        response.sendRedirect(request.getContextPath() + "/oauth2/authorization/" + registrationId);
    }

    private String resolveRegistrationId(String requestUri) {
        return properties.getRoutes().stream()
                .filter(route -> route.matches(requestUri))
                .map(SidecarProperties.Route::getClientRegistrationId)
                .findFirst()
                .orElseGet(() -> properties.getRoutes().get(0).getClientRegistrationId());
    }
}
