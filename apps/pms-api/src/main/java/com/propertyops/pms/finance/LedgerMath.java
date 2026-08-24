package com.propertyops.pms.finance;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;

import com.propertyops.pms.common.api.BusinessException;

public final class LedgerMath {
    private LedgerMath() {}

    public static BillBalance applyPayment(BigDecimal total, BigDecimal paid, BigDecimal outstanding, BigDecimal amount) {
        positive(amount);
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException("PAYMENT_EXCEEDS_OUTSTANDING", "支付金额不能超过账单待收金额", HttpStatus.CONFLICT);
        }
        BigDecimal newPaid = paid.add(amount);
        BigDecimal newOutstanding = outstanding.subtract(amount);
        if (newPaid.add(newOutstanding).compareTo(total) != 0) {
            throw new BusinessException("BALANCE_INVARIANT_FAILED", "账单余额守恒校验失败", HttpStatus.CONFLICT);
        }
        return new BillBalance(newPaid, newOutstanding, newOutstanding.signum() == 0 ? "PAID" : "PARTIAL");
    }

    public static BillBalance reversePayment(BigDecimal total, BigDecimal paid, BigDecimal outstanding, BigDecimal amount) {
        positive(amount);
        if (amount.compareTo(paid) > 0) {
            throw new BusinessException("REVERSAL_EXCEEDS_PAID", "冲正金额不能超过已收金额", HttpStatus.CONFLICT);
        }
        BigDecimal newPaid = paid.subtract(amount);
        BigDecimal newOutstanding = outstanding.add(amount);
        if (newPaid.add(newOutstanding).compareTo(total) != 0) {
            throw new BusinessException("BALANCE_INVARIANT_FAILED", "冲正后账单余额守恒校验失败", HttpStatus.CONFLICT);
        }
        return new BillBalance(newPaid, newOutstanding, newPaid.signum() == 0 ? "UNPAID" : "PARTIAL");
    }

    public static void positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("AMOUNT_MUST_BE_POSITIVE", "金额必须大于 0", HttpStatus.BAD_REQUEST);
        }
    }

    public record BillBalance(BigDecimal paid, BigDecimal outstanding, String status) {}
}
