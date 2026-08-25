package com.propertyops.pms.adapter;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pms.adapters")
public class AdapterModeProperties {
    private String payment = "simulator";
    private String invoice = "simulator";
    private String bank = "simulator";
    private String iot = "simulator";
    private String java110 = "disabled";

    @PostConstruct
    void validateFailClosedModes() {
        require("payment", payment, "simulator");
        require("invoice", invoice, "simulator");
        require("bank", bank, "simulator");
        require("iot", iot, "simulator");
        require("java110", java110, "disabled");
    }

    private void require(String adapter, String actual, String supported) {
        if (!supported.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("Adapter " + adapter + " mode '" + actual
                    + "' is not implemented; supported fail-closed mode is '" + supported + "'");
        }
    }

    public String getPayment() { return payment; }
    public void setPayment(String payment) { this.payment = payment; }
    public String getInvoice() { return invoice; }
    public void setInvoice(String invoice) { this.invoice = invoice; }
    public String getBank() { return bank; }
    public void setBank(String bank) { this.bank = bank; }
    public String getIot() { return iot; }
    public void setIot(String iot) { this.iot = iot; }
    public String getJava110() { return java110; }
    public void setJava110(String java110) { this.java110 = java110; }
}
