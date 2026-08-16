package com.poc.billing.controller;

import com.poc.billing.model.Invoice;
import com.poc.billing.repository.InvoiceRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD simples de faturas. Este controller nao valida token nem escopo: essa
 * responsabilidade e do sidecar (oauth-sidecar), que e o unico
 * ponto de entrada exposto ao mundo externo.
 */
@RestController
@RequestMapping("/billing")
@CrossOrigin(origins = "*")
public class BillingController {

    private final InvoiceRepository repository;

    public BillingController(InvoiceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Invoice> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Invoice> findById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Invoice create(@Valid @RequestBody Invoice invoice) {
        invoice.setId(null);
        return repository.save(invoice);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Invoice> update(@PathVariable Long id, @Valid @RequestBody Invoice updated) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setDescription(updated.getDescription());
                    existing.setAmount(updated.getAmount());
                    existing.setPaid(updated.isPaid());
                    return ResponseEntity.ok(repository.save(existing));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
