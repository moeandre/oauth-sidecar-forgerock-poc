package com.poc.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservico de faturas "burro": nao sabe nada de OAuth/OIDC.
 * Confia que quem chegou ate aqui ja foi validado pelo sidecar
 * (isolamento de rede: esse servico nao e publicado no host).
 */
@SpringBootApplication
public class BillingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
