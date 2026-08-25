package com.propertyops.pms.adapter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class BankTrustSimulator implements BankTrustAdapter {
    @Override
    public TrustResult submit(String batchNo, int itemCount, BigDecimal amount) {
        String reference = UUID.nameUUIDFromBytes((batchNo + ":" + itemCount + ":" + amount.toPlainString())
                .getBytes(StandardCharsets.UTF_8)).toString();
        return new TrustResult("SIMULATED_ACCEPTED", "SIM-BANK-" + reference,
                itemCount, amount, true);
    }

    @Override
    public String code() {
        return "BANK_TRUST_SIMULATOR";
    }
}
