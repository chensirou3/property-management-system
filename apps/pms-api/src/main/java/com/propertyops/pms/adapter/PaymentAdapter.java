package com.propertyops.pms.adapter;

import java.math.BigDecimal;

public interface PaymentAdapter {
    PaymentResult confirm(String orderNo, BigDecimal amount, String paymentMethod);

    String code();

    record PaymentResult(String status, String externalReference, boolean simulated) {}
}
