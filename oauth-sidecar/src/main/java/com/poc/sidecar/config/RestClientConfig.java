package com.poc.sidecar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Sem este bean explicito, {@code RestClient.create(url)} (usado antes no
 * ProxyController) monta um client com timeouts default do JDK - na pratica
 * sem timeout nenhum. Aqui aplicamos manualmente
 * {@code spring.http.client.connect-timeout}/{@code read-timeout} (os
 * mesmos nomes de propriedade que o Spring Boot documenta para essa
 * finalidade) na factory usada por todo RestClient criado a partir deste
 * builder - critico com um backend lento/instavel: sem timeout, uma chamada
 * presa consome uma thread (virtual ou nao) indefinidamente e, sob carga,
 * esgota a capacidade do sidecar de atender requisicoes novas.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${spring.http.client.connect-timeout:2s}") Duration connectTimeout,
            @Value("${spring.http.client.read-timeout:3s}") Duration readTimeout) {

        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);

        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder().requestFactory(requestFactory);
    }
}
