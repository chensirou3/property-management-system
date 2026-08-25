package com.propertyops.pms.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import com.propertyops.pms.fee.FeeModels;
import com.propertyops.pms.finance.FinanceService;
import com.propertyops.pms.meter.MeterService;
import org.junit.jupiter.api.Test;

class BusinessRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsInvalidMeterPeriodAndEmptyReadings() {
        assertThat(validator.validate(new MeterService.CreateBatch("project", "batch", "2026-13", "MANUAL")))
                .extracting(item -> item.getPropertyPath().toString())
                .contains("readingPeriod");
        assertThat(validator.validate(new MeterService.ReadingsInput("project", "batch", List.of())))
                .extracting(item -> item.getPropertyPath().toString())
                .contains("readings");
    }

    @Test
    void rejectsNonPositivePaymentAndInvalidBillingPeriod() {
        FinanceService.PaymentOrderRequest payment = new FinanceService.PaymentOrderRequest(
                "project", "SIMULATOR", List.of(new FinanceService.BillPayment("bill", BigDecimal.ZERO)));
        assertThat(validator.validate(payment)).isNotEmpty();

        FeeModels.PeriodicRequest receivable = new FeeModels.PeriodicRequest(
                "project", "2026-00", List.of("asset"));
        assertThat(validator.validate(receivable)).isNotEmpty();
    }
}
