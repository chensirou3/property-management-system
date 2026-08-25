package com.propertyops.pms.fee;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class FeeModels {
    private FeeModels() {}

    public record CreateDefinition(
            @NotBlank String communityId,
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "PROPERTY|PARKING|METER|TEMPORARY") String feeType,
            @NotBlank @Pattern(regexp = "PERIODIC|USAGE|TEMPORARY") String feeClass,
            @NotBlank @Size(max = 30) String unitCode,
            @NotNull @Min(0) @Max(2) Integer decimalScale,
            @NotNull Boolean lateFeeEnabled,
            @NotNull Boolean temporaryAllowed,
            @Size(max = 80) String accountingSubjectCode,
            @Size(max = 80) String prepaymentSubjectCode,
            @Size(max = 80) String taxCategoryCode,
            @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
            @NotBlank @Pattern(regexp = "HALF_UP|HALF_EVEN|DOWN|UP") String roundingMode,
            @NotBlank @Pattern(regexp = "CNY") String currencyCode) {}

    public record UpdateDefinition(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "PROPERTY|PARKING|METER|TEMPORARY") String feeType,
            @NotBlank @Pattern(regexp = "PERIODIC|USAGE|TEMPORARY") String feeClass,
            @NotBlank @Size(max = 30) String unitCode,
            @NotNull @Min(0) @Max(2) Integer decimalScale,
            @NotNull Boolean lateFeeEnabled,
            @NotNull Boolean temporaryAllowed,
            @Size(max = 80) String accountingSubjectCode,
            @Size(max = 80) String prepaymentSubjectCode,
            @Size(max = 80) String taxCategoryCode,
            @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal taxRate,
            @NotBlank @Pattern(regexp = "HALF_UP|HALF_EVEN|DOWN|UP") String roundingMode,
            @NotBlank @Pattern(regexp = "CNY") String currencyCode,
            @NotNull Boolean enabled,
            @NotNull @Min(0) Long expectedVersion) {}

    public record CreateStandard(
            @NotBlank String communityId,
            @NotBlank String feeDefinitionId,
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "ROOM|PARKING|METER") String assetType,
            @NotBlank @Pattern(regexp = "MONTHLY|ONCE") String billingCycle,
            @NotBlank @Pattern(regexp = "BUILDING_AREA|USABLE_AREA|FIXED|METER_USAGE") String calculationBasis,
            @NotBlank @Pattern(regexp = "FULL_PERIOD") String prorationRule,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            @DecimalMin("0") BigDecimal minimumAmount,
            @DecimalMin("0") BigDecimal maximumAmount,
            @NotBlank @Size(max = 80) String formulaCode,
            @Size(max = 1000) String formulaExpression,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record CreateStandardVersion(
            @NotBlank String communityId,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            @DecimalMin("0") BigDecimal minimumAmount,
            @DecimalMin("0") BigDecimal maximumAmount,
            @NotBlank @Size(max = 80) String formulaCode,
            @Size(max = 1000) String formulaExpression,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {}

    public record AllocationRequest(
            @NotBlank String communityId,
            @NotBlank String feeStandardId,
            @NotBlank @Pattern(regexp = "ASSET|METER") String targetType,
            @NotEmpty List<@NotBlank String> targetIds,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal coefficient,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Pattern(regexp = "MANUAL|IMPORT|MIGRATION") String sourceType) {}

    public record CancelAllocations(
            @NotBlank String communityId,
            @NotEmpty List<@NotBlank String> allocationIds,
            @NotNull LocalDate effectiveTo,
            @NotBlank @Size(max = 500) String reason) {}

    public record PeriodicRequest(
            @NotBlank String communityId,
            @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String billingPeriod,
            @NotNull List<@NotBlank String> assetIds) {}

    public record TemporaryLine(
            @NotBlank String feeDefinitionId,
            @NotBlank @Size(max = 160) String itemName,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal coefficient) {}

    public record TemporaryRequest(
            @NotBlank String communityId,
            @NotBlank String assetId,
            String customerId,
            @NotNull LocalDate chargeDate,
            @NotNull LocalDate dueDate,
            @NotEmpty List<@Valid TemporaryLine> lines) {}

    public record BatchResult(int changed, int skipped, List<Map<String, Object>> items) {}

    public record PreviewError(int rowNo, String targetId, String standardId,
                               String errorCode, String errorMessage) {}

    public record PreviewLine(int rowNo, String targetType, String targetId,
                              String assetId, String assetName, String itemName,
                              BigDecimal quantity, BigDecimal unitPrice, BigDecimal coefficient,
                              BigDecimal preRoundAmount, BigDecimal amount,
                              String configurationChecksum, Map<String, Object> snapshot) {}

    public record PreviewResult(String jobType, String billingPeriod, LocalDate chargeDate,
                                int lineCount, int errorCount, BigDecimal totalAmount,
                                String configurationChecksum, List<PreviewLine> items,
                                List<PreviewError> errors) {}

    public record JobReceipt(String jobId, String jobType, String status, int requestedCount,
                             int generatedCount, int skippedCount, int errorCount,
                             BigDecimal totalAmount, boolean replayed) {}
}
