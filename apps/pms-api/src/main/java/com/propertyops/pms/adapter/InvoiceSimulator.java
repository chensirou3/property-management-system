package com.propertyops.pms.adapter;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

@Component
public class InvoiceSimulator implements InvoiceAdapter {
    @Override
    public InvoiceResult issue(String requestNo, BigDecimal amount, String title) {
        return new InvoiceResult("SIMULATED", "SIM-INVOICE-" + requestNo, true);
    }

    @Override
    public String code() {
        return "INVOICE_SIMULATOR";
    }
}
