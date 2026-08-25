package com.propertyops.pms.migration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MigrationModels {
    private MigrationModels() {}

    public record SourceRow(
            @NotBlank @Pattern(regexp = "PROJECT|BUILDING|ASSET|CUSTOMER|RELATION") String resourceType,
            @NotBlank @Size(max = 120) String sourceId,
            @NotNull Map<String, Object> data) {}

    public record CreateBatchRequest(
            @NotBlank String communityId,
            @NotBlank @Size(max = 240) String sourceName,
            @NotBlank @Size(max = 60) String mappingVersion,
            @NotEmpty @Size(max = 500) List<@Valid @NotNull SourceRow> rows) {}

    public record ApprovalRequest(
            @NotBlank String communityId,
            boolean confirmPartial,
            @Size(max = 500) String comment,
            long expectedVersion) {}

    public record BatchCommandRequest(@NotBlank String communityId, long expectedVersion) {}

    public record RollbackRequest(
            @NotBlank String communityId,
            @NotBlank String rollbackToken,
            @NotBlank @Size(max = 500) String reason,
            long expectedVersion) {}

    public record BatchSummary(
            String id, String communityId, String batchNo, String sourceName, String mappingVersion,
            String sourceSha256, String status, String reviewStatus, int totalCount,
            int quarantineCount, int canonicalCount, int stagedCount, int importedCount,
            int skippedCount, int errorCount, long version, LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    public record QuarantineItem(int rowNo, String resourceType, String sourceId, String errorCode,
                                 String fieldName, String message) {}

    public record ObjectMapping(String resourceType, String sourceId, String targetId,
                                String targetCode, boolean active) {}

    public record ReconciliationItem(String metricName, BigDecimal sourceValue, BigDecimal targetValue,
                                     BigDecimal differenceValue, String status, Map<String, Object> detail) {}

    public record BatchEvent(String eventType, String fromStatus, String toStatus,
                             Map<String, Object> detail, LocalDateTime createdAt) {}

    public record BatchDetail(BatchSummary batch, String rollbackToken,
                              List<QuarantineItem> quarantine,
                              List<ObjectMapping> mappings,
                              List<ReconciliationItem> reconciliation,
                              List<BatchEvent> events,
                              boolean replayed) {}

    public record CommandResult(String batchId, String batchNo, String status, int importedCount,
                                int skippedCount, int errorCount, long version, boolean replayed) {}
}
