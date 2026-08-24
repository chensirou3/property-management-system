package com.propertyops.pms.adapter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class PaymentSimulator implements PaymentAdapter {
    @Override
    public PaymentResult confirm(String orderNo, BigDecimal amount, String paymentMethod) {
        String reference = UUID.nameUUIDFromBytes((orderNo + ":" + amount.toPlainString())
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new PaymentResult("SUCCESS", "SIM-" + reference, true);
    }

    @Override
    public String code() {
        return "PAYMENT_SIMULATOR";
    }
}
