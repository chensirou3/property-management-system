package com.propertyops.pms.report;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReportModels {
    private ReportModels() {}

    public record FilterOption(String value, String label) {}

    public record ReportFilterOptions(
            List<FilterOption> feeDefinitionId,
            List<FilterOption> cashierId) {}

    public record CreateExportJob(
            @NotBlank String communityId,
            @NotBlank String reportCode,
            @NotBlank String format,
            @NotNull Map<String, String> filters,
            @NotNull List<String> selectedColumns) {}

    public record CreateReceiptPrintJob(
            @NotBlank String communityId,
            @NotEmpty @Size(max = 200) List<@NotBlank String> receiptIds,
            @NotBlank String format) {}

    public record CreateNotificationBatch(
            @NotBlank String communityId,
            @NotBlank String billingPeriod,
            @NotBlank String channel,
            @NotNull @Size(max = 1000) List<@NotBlank String> billIds,
            @NotBlank @Size(max = 500) String contentTemplate) {}
}
