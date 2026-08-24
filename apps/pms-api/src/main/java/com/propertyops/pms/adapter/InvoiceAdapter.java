package com.propertyops.pms.adapter;

import java.math.BigDecimal;

public interface InvoiceAdapter {
    InvoiceResult issue(String requestNo, BigDecimal amount, String title);
    String code();

    record InvoiceResult(String status, String externalReference, boolean simulated) {}
}
