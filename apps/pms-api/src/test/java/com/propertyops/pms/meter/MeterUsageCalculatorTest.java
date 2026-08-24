package com.propertyops.pms.meter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MeterUsageCalculatorTest {
    @Test
    void calculatesMultiplierCorrectionAndShare() {
        var usage = MeterUsageCalculator.calculate(new BigDecimal("100.0000"), new BigDecimal("112.5000"),
                new BigDecimal("2"), new BigDecimal("-0.5000"), new BigDecimal("1.2500"));
        assertThat(usage.raw()).isEqualByComparingTo("12.5000");
        assertThat(usage.adjusted()).isEqualByComparingTo("25.0000");
        assertThat(usage.billable()).isEqualByComparingTo("25.7500");
    }

    @Test
    void rejectsDecreasingReadings() {
        assertThatThrownBy(() -> MeterUsageCalculator.calculate(BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO)).hasMessageContaining("不能小于");
    }
}
