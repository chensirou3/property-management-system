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

    @Test
    void includesLossRateBeforeCorrectionAndShare() {
        var usage = MeterUsageCalculator.calculate(new BigDecimal("100"), new BigDecimal("110"),
                new BigDecimal("2"), new BigDecimal("0.05"), new BigDecimal("-0.5"),
                new BigDecimal("1.0"));
        assertThat(usage.raw()).isEqualByComparingTo("10.0000");
        assertThat(usage.adjusted()).isEqualByComparingTo("21.0000");
        assertThat(usage.billable()).isEqualByComparingTo("21.5000");
    }

    @Test
    void rejectsLossRateAboveOne() {
        assertThatThrownBy(() -> MeterUsageCalculator.calculate(BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("1.01"), BigDecimal.ZERO, BigDecimal.ZERO))
                .hasMessageContaining("损耗率");
    }
}
