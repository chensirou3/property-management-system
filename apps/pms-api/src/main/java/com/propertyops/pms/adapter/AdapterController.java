package com.propertyops.pms.adapter;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.propertyops.pms.security.SecurityContextService;

@RestController
@RequestMapping("/api/v1/adapters")
public class AdapterController {
    private final PaymentAdapter payment;
    private final InvoiceAdapter invoice;
    private final Java110Adapter java110;
    private final SecurityContextService security;

    public AdapterController(PaymentAdapter payment, InvoiceAdapter invoice, Java110Adapter java110,
                             SecurityContextService security) {
        this.payment = payment;
        this.invoice = invoice;
        this.java110 = java110;
        this.security = security;
    }

    @GetMapping("/status")
    Map<String, Object> status() {
        security.requirePermission("dashboard:read");
        return Map.of(
                "payment", Map.of("adapter", payment.code(), "mode", "SIMULATOR", "productionReady", false),
                "invoice", Map.of("adapter", invoice.code(), "mode", "SIMULATOR", "productionReady", false),
                "iot", Map.of("adapter", "IOT_SIMULATOR", "mode", "SIMULATOR", "productionReady", false),
                "java110", Map.of("adapter", java110.code(), "mode", java110.enabled() ? "ENABLED" : "DISABLED",
                        "productionReady", false)
        );
    }
}
