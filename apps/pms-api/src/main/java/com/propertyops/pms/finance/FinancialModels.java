package com.propertyops.pms.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class FinancialModels {
    private FinancialModels() {}

    public record OpenShift(
            @NotBlank String communityId,
            @NotNull @DecimalMin("0") BigDecimal openingCash) {}

    public record CloseShift(
            @NotBlank String communityId,
            @NotNull @DecimalMin("0") BigDecimal actualCash,
            @NotNull Long expectedVersion) {}

    public record LockShift(
            @NotBlank String communityId,
            @NotNull Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record SettlementRequest(
            @NotBlank String communityId,
            @NotNull LocalDate settlementDate) {}

    public record SettlementLock(
            @NotBlank String communityId,
            @NotNull Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record CreateDiscountPolicy(
            @NotBlank String communityId,
            @NotBlank @Size(max = 80) String policyCode,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank String discountType,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal discountValue,
            @DecimalMin("0") BigDecimal maximumAmount,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean approvalRequired) {}

    public record UpdateDiscountPolicy(
            @NotBlank @Size(max = 160) String displayName,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal discountValue,
            @DecimalMin("0") BigDecimal maximumAmount,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank String status,
            boolean approvalRequired,
            @NotNull Long expectedVersion) {}

    public record CreateAdjustment(
            @NotBlank String communityId,
            @NotBlank String billId,
            String discountPolicyId,
            @NotBlank String adjustmentType,
            @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {}

    public record AdjustmentDecision(
            @NotBlank String communityId,
            @NotNull Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record CreateReceiptSegment(
            @NotBlank String communityId,
            @NotBlank @Size(max = 80) String segmentCode,
            @NotBlank @Size(max = 40) String numberPrefix,
            @NotNull @DecimalMin("1") Long startNo,
            @NotNull @DecimalMin("1") Long endNo) {}

    public record ReceiptOperation(
            @NotBlank String communityId,
            @NotBlank @Size(max = 500) String reason) {}

    public record InvoiceOperation(
            @NotBlank String communityId,
            @NotBlank String operationType,
            @NotBlank String title,
            @NotBlank @Size(max = 500) String reason) {}
}
