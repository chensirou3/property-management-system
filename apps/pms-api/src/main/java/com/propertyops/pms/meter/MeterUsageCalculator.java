package com.propertyops.pms.meter;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;

import com.propertyops.pms.common.api.BusinessException;

public final class MeterUsageCalculator {
    private MeterUsageCalculator() {}

    public static Usage calculate(BigDecimal previous, BigDecimal current, BigDecimal multiplier,
                                  BigDecimal correction, BigDecimal allocatedShare) {
        if (previous == null || current == null || multiplier == null) {
            throw new BusinessException("METER_READING_REQUIRED", "读数和倍率不能为空", HttpStatus.BAD_REQUEST);
        }
        BigDecimal raw = current.subtract(previous);
        if (raw.signum() < 0) {
            throw new BusinessException("METER_READING_DECREASED", "本期读数不能小于上期读数", HttpStatus.BAD_REQUEST);
        }
        if (multiplier.signum() <= 0) {
            throw new BusinessException("INVALID_METER_MULTIPLIER", "仪表倍率必须大于 0", HttpStatus.BAD_REQUEST);
        }
        BigDecimal safeCorrection = correction == null ? BigDecimal.ZERO : correction;
        BigDecimal safeShare = allocatedShare == null ? BigDecimal.ZERO : allocatedShare;
        BigDecimal adjusted = raw.multiply(multiplier);
        BigDecimal billable = adjusted.add(safeCorrection).add(safeShare);
        if (billable.signum() < 0) {
            throw new BusinessException("NEGATIVE_BILLABLE_USAGE", "计费用量不能为负数", HttpStatus.BAD_REQUEST);
        }
        return new Usage(raw.setScale(4, RoundingMode.HALF_UP), adjusted.setScale(4, RoundingMode.HALF_UP),
                billable.setScale(4, RoundingMode.HALF_UP));
    }

    public record Usage(BigDecimal raw, BigDecimal adjusted, BigDecimal billable) {}
}
