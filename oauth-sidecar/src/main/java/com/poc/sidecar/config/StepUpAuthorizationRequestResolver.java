package com.poc.sidecar.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Envolve o resolver padrao do Spring Security. Quando a requisicao de
 * autorizacao chega com "?reauth=true&amp;scope=write" (usado pelo
 * ProxyController quando o token atual nao tem o escopo necessario), duas
 * coisas mudam em relacao a um login normal:
 *
 * 1. Adicionamos "prompt=consent", para o AM reexibir a tela de
 *    consentimento mesmo que o usuario ja tenha uma sessao/consentimento
 *    anterior (sem isso, o AM poderia simplesmente pular a tela e
 *    devolver o codigo sem o usuario ver nada novo).
 * 2. Acrescentamos o escopo pedido no parametro "scope" ao pedido de
 *    autorizacao. O login normal (application.yml) so pede "openid read" -
 *    "write" nunca e solicitado de antemao. Assim o AM so exibe/pede
 *    consentimento pelo escopo que esta de fato sendo solicitado *naquele*
 *    momento: nada no login comum (read e concedido silenciosamente, sem
 *    tela), e so "write" no momento do step-up.
 */
public class StepUpAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver delegate;

    public StepUpAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest authRequest, HttpServletRequest request) {
        if (authRequest == null) {
            return null;
        }
        if (!"true".equals(request.getParameter("reauth"))) {
            return authRequest;
        }

        Map<String, Object> extra = new HashMap<>(authRequest.getAdditionalParameters());
        extra.put("prompt", "consent");

        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.from(authRequest)
                .additionalParameters(extra);

        String extraScope = request.getParameter("scope");
        if (extraScope != null && !extraScope.isBlank()) {
            Set<String> scopes = new LinkedHashSet<>(authRequest.getScopes());
            scopes.add(extraScope);
            builder.scopes(scopes);
        }

        return builder.build();
    }
}
