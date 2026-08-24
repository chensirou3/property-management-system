package com.propertyops.pms.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class LedgerMathTest {
    @Test
    void partialPaymentAndReversalPreserveBalance() {
        var paid = LedgerMath.applyPayment(new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("35.20"));
        assertThat(paid.paid()).isEqualByComparingTo("35.20");
        assertThat(paid.outstanding()).isEqualByComparingTo("64.80");
        assertThat(paid.status()).isEqualTo("PARTIAL");

        var reversed = LedgerMath.reversePayment(new BigDecimal("100.00"), paid.paid(),
                paid.outstanding(), new BigDecimal("35.20"));
        assertThat(reversed.paid()).isEqualByComparingTo("0.00");
        assertThat(reversed.outstanding()).isEqualByComparingTo("100.00");
        assertThat(reversed.status()).isEqualTo("UNPAID");
    }

    @Test
    void rejectsOverpayment() {
        assertThatThrownBy(() -> LedgerMath.applyPayment(new BigDecimal("100.00"), BigDecimal.ZERO,
                new BigDecimal("100.00"), new BigDecimal("100.01")))
                .hasMessageContaining("超过");
    }
}
