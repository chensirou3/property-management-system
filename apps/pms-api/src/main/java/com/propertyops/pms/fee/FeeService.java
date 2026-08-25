package com.propertyops.pms.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class FeeService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public FeeService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                      AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> createStandard(CreateStandard request) {
        security.requirePermission("fee:write");
        security.requireProject(request.communityId());
        Long definitionCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_definition
                WHERE id=:definitionId AND community_id=:communityId AND enabled=TRUE
                """, Map.of("definitionId", request.feeDefinitionId(), "communityId", request.communityId()), Long.class);
        if (definitionCount == null || definitionCount == 0) throw invalid("费用定义不存在或已停用");
        String standardId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO fee_standard
                    (id, fee_definition_id, code, name, asset_type, billing_cycle, status, version, created_at, updated_at)
                VALUES (:id, :definitionId, :code, :name, :assetType, :cycle, 'ACTIVE', 0, :now, :now)
                """, Map.of("id", standardId, "definitionId", request.feeDefinitionId(), "code", request.code(),
                "name", request.name(), "assetType", request.assetType(), "cycle", request.billingCycle(), "now", now));
        var versionParams = new MapSqlParameterSource()
                .addValue("id", versionId).addValue("standardId", standardId).addValue("unitPrice", request.unitPrice())
                .addValue("formulaCode", request.formulaCode()).addValue("expression", request.formulaExpression())
                .addValue("from", request.effectiveFrom()).addValue("to", request.effectiveTo()).addValue("now", now);
        jdbc.update("""
                INSERT INTO fee_standard_version
                    (id, fee_standard_id, version_no, unit_price, formula_code, formula_expression,
                     effective_from, effective_to, status, created_at)
                VALUES (:id, :standardId, 1, :unitPrice, :formulaCode, :expression,
                        :from, :to, 'ACTIVE', :now)
                """, versionParams);
        audit.success(request.communityId(), "fee-standard:create", "fee-standard", standardId,
                Map.of("versionId", versionId, "formula", request.formulaCode()));
        return Map.of("id", standardId, "versionId", versionId, "versionNo", 1, "status", "ACTIVE");
    }

    @Transactional
    public BatchResult batchAssign(BatchAssign request) {
        security.requirePermission("fee:write");
        security.requireProject(request.communityId());
        validateStandard(request.communityId(), request.feeStandardId());
        List<String> validAssets = jdbc.queryForList("""
                SELECT id FROM asset WHERE community_id=:communityId AND id IN (:ids) AND enabled=TRUE
                """, new MapSqlParameterSource("communityId", request.communityId()).addValue("ids", request.assetIds()), String.class);
        if (validAssets.size() != request.assetIds().stream().distinct().count()) throw invalid("部分资产不存在、已停用或不属于当前项目");
        int assigned = 0;
        int skipped = 0;
        LocalDateTime now = now();
        for (String assetId : validAssets) {
            int changed = jdbc.update("""
                    INSERT IGNORE INTO fee_allocation
                        (id, fee_standard_id, asset_id, coefficient, effective_from, effective_to,
                         status, version, created_at, updated_at)
                    VALUES (:id, :standardId, :assetId, :coefficient, :from, :to,
                            'ACTIVE', 0, :now, :now)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString()).addValue("standardId", request.feeStandardId())
                    .addValue("assetId", assetId).addValue("coefficient", request.coefficient())
                    .addValue("from", request.effectiveFrom()).addValue("to", request.effectiveTo()).addValue("now", now));
            if (changed == 1) assigned++; else skipped++;
        }
        audit.success(request.communityId(), "fee-allocation:batch-assign", "fee-allocation", request.feeStandardId(),
                Map.of("assigned", assigned, "skipped", skipped));
        return new BatchResult(assigned, skipped);
    }

    @Transactional
    public BatchResult batchCancel(BatchCancel request) {
        security.requirePermission("fee:write");
        security.requireProject(request.communityId());
        int changed = jdbc.update("""
                UPDATE fee_allocation fa JOIN asset a ON a.id=fa.asset_id
                SET fa.status='INACTIVE', fa.version=fa.version+1, fa.updated_at=:now
                WHERE a.community_id=:communityId AND fa.id IN (:ids) AND fa.status='ACTIVE'
                """, new MapSqlParameterSource("communityId", request.communityId())
                .addValue("ids", request.allocationIds()).addValue("now", now()));
        audit.success(request.communityId(), "fee-allocation:batch-cancel", "fee-allocation", null,
                Map.of("cancelled", changed));
        return new BatchResult(changed, request.allocationIds().size() - changed);
    }

    public PreviewResult preview(ReceivableRequest request) {
        security.requirePermission("fee:read");
        security.requireProject(request.communityId());
        return calculate(request);
    }

    @Transactional
    public GenerationResult generate(ReceivableRequest request, String idempotencyKey) {
        security.requirePermission("fee:write");
        security.requireProject(request.communityId());
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "生成应收必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, generated_count, error_count, status FROM receivable_generation_job
                WHERE community_id=:communityId AND request_key=:requestKey
                """, Map.of("communityId", request.communityId(), "requestKey", idempotencyKey));
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            return new GenerationResult(String.valueOf(row.get("id")), String.valueOf(row.get("status")),
                    ((Number) row.get("generated_count")).intValue(), ((Number) row.get("error_count")).intValue(), true);
        }
        PreviewResult preview = calculate(request);
        String jobId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO receivable_generation_job
                    (id, community_id, billing_period, request_key, status, preview, generated_count,
                     error_count, requested_by, started_at, created_at)
                VALUES (:id, :communityId, :period, :requestKey, 'RUNNING', FALSE, 0, 0, :userId, :now, :now)
                """, Map.of("id", jobId, "communityId", request.communityId(), "period", request.billingPeriod(),
                "requestKey", idempotencyKey, "userId", security.requirePrincipal().userId(), "now", now));

        int generated = 0;
        int errors = 0;
        Map<String, List<PreviewLine>> byAsset = preview.items().stream().collect(Collectors.groupingBy(
                PreviewLine::assetId, LinkedHashMap::new, Collectors.toList()));
        YearMonth period = YearMonth.parse(request.billingPeriod());
        for (var group : byAsset.entrySet()) {
            String assetId = group.getKey();
            String billNo = "RCV-" + request.billingPeriod().replace("-", "") + "-" + assetId;
            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM bill WHERE bill_no=:billNo",
                    Map.of("billNo", billNo), Long.class);
            if (exists != null && exists > 0) continue;
            try {
                String billId = UUID.randomUUID().toString();
                BigDecimal total = group.getValue().stream().map(PreviewLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                List<String> customers = jdbc.queryForList("""
                        SELECT customer_id FROM customer_asset_relation
                        WHERE asset_id=:assetId AND status='ACTIVE'
                        ORDER BY primary_relation DESC, created_at ASC LIMIT 1
                        """, Map.of("assetId", assetId), String.class);
                var billParams = new MapSqlParameterSource()
                        .addValue("id", billId).addValue("communityId", request.communityId()).addValue("assetId", assetId)
                        .addValue("customerId", customers.isEmpty() ? null : customers.get(0)).addValue("billNo", billNo)
                        .addValue("period", request.billingPeriod()).addValue("total", total)
                        .addValue("dueDate", period.atEndOfMonth()).addValue("now", now);
                jdbc.update("""
                        INSERT INTO bill
                            (id, community_id, asset_id, customer_id, bill_no, billing_period, status,
                             total_amount, paid_amount, outstanding_amount, due_date, version, created_at, updated_at)
                        VALUES (:id, :communityId, :assetId, :customerId, :billNo, :period, 'UNPAID',
                                :total, 0, :total, :dueDate, 0, :now, :now)
                        """, billParams);
                for (PreviewLine line : group.getValue()) {
                    jdbc.update("""
                            INSERT INTO bill_item
                                (id, bill_id, fee_definition_id, fee_standard_version_id, item_name_snapshot,
                                 quantity, unit_price, coefficient, amount, calculation_snapshot, created_at)
                            VALUES (:id, :billId, :definitionId, :versionId, :name,
                                    :quantity, :unitPrice, :coefficient, :amount, :snapshot, :now)
                            """, new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID().toString()).addValue("billId", billId)
                            .addValue("definitionId", line.feeDefinitionId()).addValue("versionId", line.standardVersionId())
                            .addValue("name", line.itemName()).addValue("quantity", line.quantity())
                            .addValue("unitPrice", line.unitPrice()).addValue("coefficient", line.coefficient())
                            .addValue("amount", line.amount()).addValue("snapshot", json(line)).addValue("now", now));
                }
                generated++;
            } catch (RuntimeException exception) {
                errors++;
                jdbc.update("""
                        INSERT INTO receivable_generation_error
                            (id, job_id, asset_id, error_code, error_message, created_at)
                        VALUES (:id, :jobId, :assetId, 'GENERATION_FAILED', :message, :now)
                        """, Map.of("id", UUID.randomUUID().toString(), "jobId", jobId, "assetId", assetId,
                        "message", safeMessage(exception), "now", now));
            }
        }
        String status = errors == 0 ? "COMPLETED" : generated > 0 ? "PARTIAL" : "FAILED";
        jdbc.update("""
                UPDATE receivable_generation_job
                SET status=:status, generated_count=:generated, error_count=:errors, completed_at=:now
                WHERE id=:id
                """, Map.of("status", status, "generated", generated, "errors", errors, "now", now, "id", jobId));
        jdbc.update("""
                INSERT INTO outbox_event
                    (id, aggregate_type, aggregate_id, event_type, payload_json, status, available_at, retry_count, created_at)
                VALUES (:id, 'RECEIVABLE_JOB', :jobId, 'ReceivablesGenerated', :payload, 'PENDING', :now, 0, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "jobId", jobId,
                "payload", json(Map.of("generated", generated, "errors", errors)), "now", now));
        audit.success(request.communityId(), "receivable:generate", "receivable-job", jobId,
                Map.of("generated", generated, "errors", errors, "billingPeriod", request.billingPeriod()));
        return new GenerationResult(jobId, status, generated, errors, false);
    }

    private PreviewResult calculate(ReceivableRequest request) {
        YearMonth period;
        try {
            period = YearMonth.parse(request.billingPeriod());
        } catch (RuntimeException exception) {
            throw invalid("账期必须使用 YYYY-MM 格式");
        }
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", request.communityId())
                .addValue("start", start).addValue("end", end);
        String assetPredicate = "";
        if (request.assetIds() != null && !request.assetIds().isEmpty()) {
            params.addValue("assetIds", request.assetIds());
            assetPredicate = " AND a.id IN (:assetIds)";
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT fa.id allocation_id, a.id asset_id, a.display_name, a.building_area,
                       fd.id fee_definition_id, fd.name item_name,
                       fsv.id standard_version_id, fsv.unit_price, fsv.formula_code, fa.coefficient
                FROM fee_allocation fa
                JOIN asset a ON a.id=fa.asset_id
                JOIN fee_standard fs ON fs.id=fa.fee_standard_id
                JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                JOIN fee_standard_version fsv ON fsv.fee_standard_id=fs.id
                WHERE a.community_id=:communityId
                  AND fa.status='ACTIVE' AND fs.status='ACTIVE' AND fd.enabled=TRUE AND fsv.status='ACTIVE'
                  AND fa.effective_from <= :end AND (fa.effective_to IS NULL OR fa.effective_to >= :start)
                  AND fsv.effective_from <= :end AND (fsv.effective_to IS NULL OR fsv.effective_to >= :start)
                """ + assetPredicate + " ORDER BY a.code, fd.code", params);
        List<PreviewLine> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            String formula = String.valueOf(row.get("formula_code"));
            BigDecimal quantity = "AREA_PRICE".equals(formula) ? decimal(row.get("building_area")) : BigDecimal.ONE;
            BigDecimal unitPrice = decimal(row.get("unit_price"));
            BigDecimal coefficient = decimal(row.get("coefficient"));
            BigDecimal amount = quantity.multiply(unitPrice).multiply(coefficient).setScale(2, RoundingMode.HALF_UP);
            PreviewLine line = new PreviewLine(String.valueOf(row.get("allocation_id")), String.valueOf(row.get("asset_id")),
                    String.valueOf(row.get("display_name")), String.valueOf(row.get("fee_definition_id")),
                    String.valueOf(row.get("item_name")), String.valueOf(row.get("standard_version_id")),
                    formula, quantity, unitPrice, coefficient, amount, true);
            items.add(line);
            total = total.add(amount);
        }
        return new PreviewResult(request.billingPeriod(), items.size(), total, items);
    }

    private void validateStandard(String communityId, String standardId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_standard fs JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                WHERE fs.id=:standardId AND fd.community_id=:communityId AND fs.status='ACTIVE'
                """, Map.of("standardId", standardId, "communityId", communityId), Long.class);
        if (count == null || count == 0) throw invalid("费用标准不存在或已停用");
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize calculation snapshot", exception);
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage() == null ? "generation failed" : exception.getMessage();
        return message.substring(0, Math.min(message.length(), 480));
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_FEE_CONFIGURATION", message, HttpStatus.BAD_REQUEST);
    }

    public record CreateStandard(@NotBlank String communityId, @NotBlank String feeDefinitionId,
                                 @NotBlank @Size(max = 80) String code, @NotBlank @Size(max = 160) String name,
                                 @NotBlank @Pattern(regexp = "ROOM|PARKING") String assetType,
                                 @NotBlank String billingCycle,
                                 @NotNull @DecimalMin("0") BigDecimal unitPrice,
                                 @NotBlank String formulaCode, @NotBlank String formulaExpression,
                                 @NotNull LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record BatchAssign(@NotBlank String communityId, @NotBlank String feeStandardId,
                              @NotEmpty List<@NotBlank String> assetIds,
                              @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal coefficient,
                              @NotNull LocalDate effectiveFrom, LocalDate effectiveTo) {}
    public record BatchCancel(@NotBlank String communityId, @NotEmpty List<@NotBlank String> allocationIds) {}
    public record ReceivableRequest(@NotBlank String communityId,
                                    @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String billingPeriod,
                                    @NotEmpty List<@NotBlank String> assetIds) {}
    public record BatchResult(int changed, int skipped) {}
    public record PreviewResult(String billingPeriod, int lineCount, BigDecimal totalAmount, List<PreviewLine> items) {}
    public record PreviewLine(String allocationId, String assetId, String assetName, String feeDefinitionId,
                              String itemName, String standardVersionId, String formulaCode,
                              BigDecimal quantity, BigDecimal unitPrice, BigDecimal coefficient,
                              BigDecimal amount, boolean assumptionRule) {}
    public record GenerationResult(String jobId, String status, int generatedCount, int errorCount, boolean replayed) {}
}
