package com.poc.crud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservico CRUD "burro": nao sabe nada de OAuth/OIDC.
 * Confia que quem chegou ate aqui ja foi validado pelo sidecar
 * (isolamento de rede: esse servico nao e publicado no host).
 */
@SpringBootApplication
public class CrudServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrudServiceApplication.class, args);
    }
}
