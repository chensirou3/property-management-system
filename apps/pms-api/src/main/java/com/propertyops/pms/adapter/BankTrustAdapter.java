package com.propertyops.pms.adapter;

import java.math.BigDecimal;

public interface BankTrustAdapter {
    TrustResult submit(String batchNo, int itemCount, BigDecimal amount);

    String code();

    record TrustResult(String status, String externalReference, int acceptedCount,
                       BigDecimal acceptedAmount, boolean simulated) {}
}
