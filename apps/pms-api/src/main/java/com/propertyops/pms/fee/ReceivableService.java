package com.propertyops.pms.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class ReceivableService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final ReceivableJobProcessor processor;

    public ReceivableService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                             AuditService audit, ObjectMapper objectMapper, ReceivableJobProcessor processor) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.processor = processor;
    }

    public FeeModels.PreviewResult previewPeriodic(FeeModels.PeriodicRequest request) {
        requireRead(request.communityId());
        YearMonth period = parsePeriod(request.billingPeriod());
        return calculatePeriodic(request.communityId(), period, normalizedIds(request.assetIds()));
    }

    public FeeModels.PreviewResult previewTemporary(FeeModels.TemporaryRequest request) {
        requireRead(request.communityId());
        return calculateTemporary(request);
    }

    @Transactional
    public FeeModels.JobReceipt createPeriodic(FeeModels.PeriodicRequest request, String idempotencyKey) {
        requireWrite(request.communityId());
        YearMonth period = parsePeriod(request.billingPeriod());
        List<String> assetIds = normalizedIds(request.assetIds());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("jobType", "PERIODIC");
        canonical.put("communityId", request.communityId());
        canonical.put("billingPeriod", period.toString());
        canonical.put("assetIds", assetIds);
        FeeModels.PreviewResult preview = calculatePeriodic(request.communityId(), period, assetIds);
        return createJob(request.communityId(), "PERIODIC", period.toString(), canonical, preview,
                requireIdempotencyKey(idempotencyKey));
    }

    @Transactional
    public FeeModels.JobReceipt createTemporary(FeeModels.TemporaryRequest request, String idempotencyKey) {
        requireWrite(request.communityId());
        FeeModels.PreviewResult preview = calculateTemporary(request);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("jobType", "TEMPORARY");
        canonical.put("communityId", request.communityId());
        canonical.put("assetId", request.assetId());
        canonical.put("customerId", request.customerId());
        canonical.put("chargeDate", request.chargeDate());
        canonical.put("dueDate", request.dueDate());
        canonical.put("lines", request.lines());
        return createJob(request.communityId(), "TEMPORARY", YearMonth.from(request.chargeDate()).toString(),
                canonical, preview, requireIdempotencyKey(idempotencyKey));
    }

    public List<Map<String, Object>> jobs(String communityId, String jobType) {
        requireRead(communityId);
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId);
        String predicate = "";
        if (jobType != null && !jobType.isBlank()) {
            if (!List.of("PERIODIC", "TEMPORARY").contains(jobType)) throw invalid("不支持的应收任务类型");
            predicate = " AND job_type=:jobType";
            params.addValue("jobType", jobType);
        }
        return jdbc.queryForList("""
                SELECT id, community_id communityId, job_type jobType, billing_period billingPeriod,
                       request_key requestKey, status, requested_count requestedCount,
                       generated_count generatedCount, skipped_count skippedCount,
                       error_count errorCount, total_amount totalAmount, requested_by requestedBy,
                       started_at startedAt, completed_at completedAt, created_at createdAt, updated_at updatedAt
                FROM receivable_generation_job WHERE community_id=:communityId
                """ + predicate + " ORDER BY created_at DESC LIMIT 100", params);
    }

    public Map<String, Object> job(String id, String communityId) {
        requireRead(communityId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, community_id communityId, job_type jobType, billing_period billingPeriod,
                       request_key requestKey, request_hash requestHash, request_json request,
                       status, requested_count requestedCount, generated_count generatedCount,
                       skipped_count skippedCount, error_count errorCount, total_amount totalAmount,
                       requested_by requestedBy, started_at startedAt, completed_at completedAt,
                       created_at createdAt, updated_at updatedAt
                FROM receivable_generation_job WHERE id=:id AND community_id=:communityId
                """, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("应收任务不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", jdbc.queryForList("""
                SELECT id, row_no rowNo, asset_id assetId, customer_id customerId,
                       fee_definition_id feeDefinitionId, fee_standard_version_id feeStandardVersionId,
                       fee_allocation_id feeAllocationId, item_name itemName, amount, status,
                       bill_id billId, snapshot_json snapshot, error_code errorCode,
                       error_message errorMessage, created_at createdAt, updated_at updatedAt
                FROM receivable_generation_item WHERE job_id=:id ORDER BY row_no
                """, Map.of("id", id)));
        result.put("errors", jdbc.queryForList("""
                SELECT id, row_no rowNo, asset_id assetId, fee_standard_id feeStandardId,
                       error_code errorCode, error_message errorMessage, detail_json detail,
                       created_at createdAt
                FROM receivable_generation_error WHERE job_id=:id ORDER BY row_no, created_at
                """, Map.of("id", id)));
        result.put("reconciliation", jdbc.queryForList("""
                SELECT metric_name metricName, source_value sourceValue, target_value targetValue,
                       difference_value differenceValue, status, detail_json detail, created_at createdAt
                FROM receivable_generation_reconciliation WHERE job_id=:id ORDER BY metric_name
                """, Map.of("id", id)));
        return result;
    }

    private FeeModels.JobReceipt createJob(String communityId, String jobType, String period,
                                           Map<String, Object> canonicalRequest,
                                           FeeModels.PreviewResult preview, String idempotencyKey) {
        String requestJson = json(canonicalRequest);
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT * FROM receivable_generation_job
                WHERE community_id=:communityId AND request_key=:requestKey FOR UPDATE
                """, Map.of("communityId", communityId, "requestKey", idempotencyKey));
        if (!existing.isEmpty()) return replay(existing.get(0), requestHash);

        String jobId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        int requestedCount = preview.lineCount() + preview.errorCount();
        MapSqlParameterSource params = new MapSqlParameterSource("id", jobId).addValue("communityId", communityId)
                .addValue("jobType", jobType).addValue("period", period).addValue("requestKey", idempotencyKey)
                .addValue("requestHash", requestHash).addValue("requestJson", requestJson)
                .addValue("requestedCount", requestedCount).addValue("totalAmount", preview.totalAmount())
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now);
        try {
            jdbc.update("""
                    INSERT INTO receivable_generation_job
                        (id, community_id, job_type, billing_period, request_key, request_hash, request_json,
                         status, preview, requested_count, generated_count, skipped_count, error_count,
                         total_amount, requested_by, version, created_at, updated_at)
                    VALUES (:id, :communityId, :jobType, :period, :requestKey, :requestHash, :requestJson,
                            'QUEUED', FALSE, :requestedCount, 0, 0, 0, :totalAmount,
                            :userId, 0, :now, :now)
                    """, params);
        } catch (DuplicateKeyException exception) {
            Map<String, Object> winner = jdbc.queryForMap("""
                    SELECT * FROM receivable_generation_job
                    WHERE community_id=:communityId AND request_key=:requestKey
                    """, Map.of("communityId", communityId, "requestKey", idempotencyKey));
            return replay(winner, requestHash);
        }

        for (FeeModels.PreviewLine line : preview.items()) {
            Map<String, Object> snapshot = line.snapshot();
            jdbc.update("""
                    INSERT INTO receivable_generation_item
                        (id, job_id, row_no, asset_id, customer_id, fee_definition_id,
                         fee_standard_version_id, fee_allocation_id, item_name, amount, status,
                         snapshot_json, created_at, updated_at)
                    VALUES (:id, :jobId, :rowNo, :assetId, :customerId, :definitionId,
                            :standardVersionId, :allocationId, :itemName, :amount, 'PENDING',
                            :snapshot, :now, :now)
                    """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("jobId", jobId)
                    .addValue("rowNo", line.rowNo()).addValue("assetId", line.assetId())
                    .addValue("customerId", snapshot.get("customerId")).addValue("definitionId", snapshot.get("feeDefinitionId"))
                    .addValue("standardVersionId", snapshot.get("feeStandardVersionId"))
                    .addValue("allocationId", snapshot.get("feeAllocationId"))
                    .addValue("itemName", line.itemName()).addValue("amount", line.amount())
                    .addValue("snapshot", json(snapshot)).addValue("now", now));
        }
        for (FeeModels.PreviewError error : preview.errors()) {
            insertError(jobId, error.rowNo(), error.targetId(), error.standardId(),
                    error.errorCode(), error.errorMessage(), Map.of("phase", "PREVIEW"), now);
        }
        audit.success(communityId, "receivable-job:create", "receivable-job", jobId,
                Map.of("jobType", jobType, "requestedCount", requestedCount,
                        "previewErrors", preview.errorCount(), "configurationChecksum", preview.configurationChecksum()));
        scheduleAfterCommit(jobId);
        return new FeeModels.JobReceipt(jobId, jobType, "QUEUED", requestedCount,
                0, 0, preview.errorCount(), preview.totalAmount(), false);
    }

    private FeeModels.PreviewResult calculatePeriodic(String communityId, YearMonth period, List<String> assetIds) {
        LocalDate chargeDate = period.atDay(1);
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId)
                .addValue("chargeDate", chargeDate).addValue("period", period.toString());
        String assetPredicate = "";
        if (!assetIds.isEmpty()) {
            params.addValue("assetIds", assetIds);
            assetPredicate = " AND a.id IN (:assetIds)";
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT fa.id allocation_id, fa.version allocation_version, fa.target_type,
                       fa.target_identity target_id, fa.effective_from allocation_from,
                       fa.effective_to allocation_to, fa.coefficient, fa.source_type allocation_source,
                       a.id asset_id, a.display_name asset_name, a.building_area, a.usable_area,
                       (SELECT car.customer_id FROM customer_asset_relation car
                        WHERE car.asset_id=a.id AND car.status='ACTIVE'
                        ORDER BY car.primary_relation DESC, car.created_at LIMIT 1) customer_id,
                       fd.id definition_id, fd.code definition_code, fd.name definition_name,
                       fd.version definition_version, fd.decimal_scale, fd.rounding_mode,
                       fd.currency_code, fd.tax_category_code, fd.tax_rate,
                       fs.id standard_id, fs.code standard_code, fs.version standard_version,
                       fs.calculation_basis, fs.proration_rule,
                       fsv.id standard_version_id, fsv.version_no, fsv.unit_price,
                       fsv.minimum_amount, fsv.maximum_amount, fsv.formula_code,
                       fsv.formula_expression, fsv.effective_from version_from,
                       fsv.effective_to version_to,
                       (SELECT mr.billable_usage
                        FROM meter_reading mr
                        JOIN meter_reading_batch mb ON mb.id=mr.batch_id
                        WHERE mr.meter_id=m.id AND mr.status='APPROVED' AND mb.status='APPROVED'
                          AND mb.reading_period=:period
                        ORDER BY mr.updated_at DESC LIMIT 1) meter_usage
                FROM fee_allocation fa
                JOIN fee_standard fs ON fs.id=fa.fee_standard_id
                JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                LEFT JOIN meter m ON m.id=fa.meter_id
                LEFT JOIN asset a ON a.id=CASE WHEN fa.target_type='ASSET' THEN fa.asset_id ELSE m.asset_id END
                JOIN fee_standard_version fsv ON fsv.fee_standard_id=fs.id
                    AND fsv.status='ACTIVE' AND fsv.effective_from<=:chargeDate
                    AND (fsv.effective_to IS NULL OR fsv.effective_to>=:chargeDate)
                WHERE fa.community_id=:communityId AND fa.status='ACTIVE'
                  AND fs.status='ACTIVE' AND fd.enabled=TRUE
                  AND fs.calculation_basis<>'METER_USAGE'
                  AND fa.effective_from<=:chargeDate
                  AND (fa.effective_to IS NULL OR fa.effective_to>=:chargeDate)
                """ + assetPredicate + " ORDER BY a.code, fd.code, fa.id, fsv.version_no", params);

        Map<String, List<Map<String, Object>>> byAllocation = rows.stream().collect(Collectors.groupingBy(
                row -> String.valueOf(row.get("allocation_id")), LinkedHashMap::new, Collectors.toList()));
        List<FeeModels.PreviewLine> lines = new ArrayList<>();
        List<FeeModels.PreviewError> errors = new ArrayList<>();
        int rowNo = 0;
        for (List<Map<String, Object>> versions : byAllocation.values()) {
            rowNo++;
            Map<String, Object> row = versions.get(0);
            String targetId = text(row.get("target_id"));
            String standardId = text(row.get("standard_id"));
            if (versions.size() != 1) {
                errors.add(new FeeModels.PreviewError(rowNo, targetId, standardId,
                        "AMBIGUOUS_STANDARD_VERSION", "计费日期命中多个费用标准版本"));
                continue;
            }
            if (row.get("asset_id") == null) {
                errors.add(new FeeModels.PreviewError(rowNo, targetId, standardId,
                        "MISSING_METER_ASSET", "仪表未绑定可计费资产"));
                continue;
            }
            String basis = text(row.get("calculation_basis"));
            BigDecimal quantity;
            if ("BUILDING_AREA".equals(basis)) quantity = decimal(row.get("building_area"));
            else if ("USABLE_AREA".equals(basis)) quantity = decimal(row.get("usable_area"));
            else if ("FIXED".equals(basis)) quantity = BigDecimal.ONE;
            else if ("METER_USAGE".equals(basis) && row.get("meter_usage") != null) quantity = decimal(row.get("meter_usage"));
            else {
                errors.add(new FeeModels.PreviewError(rowNo, targetId, standardId,
                        "METER_READING_REQUIRED", "计费周期缺少已审核仪表读数"));
                continue;
            }
            lines.add(lineFromConfiguration(rowNo, period.toString(), chargeDate, row, quantity));
        }
        return preview("PERIODIC", period.toString(), chargeDate, lines, errors);
    }

    private FeeModels.PreviewResult calculateTemporary(FeeModels.TemporaryRequest request) {
        if (request.dueDate().isBefore(request.chargeDate())) throw invalid("到期日不能早于计费日期");
        List<Map<String, Object>> assets = jdbc.queryForList("""
                SELECT id, display_name FROM asset
                WHERE id=:id AND community_id=:communityId AND enabled=TRUE
                """, Map.of("id", request.assetId(), "communityId", request.communityId()));
        if (assets.isEmpty()) throw notFound("临时应收资产不存在或已停用");
        String customerId = request.customerId();
        if (customerId != null && !customerId.isBlank()) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id=:id AND community_id=:communityId",
                    Map.of("id", customerId, "communityId", request.communityId()), Long.class);
            if (count == null || count == 0) throw notFound("临时应收客户不存在");
        } else {
            List<String> customers = jdbc.queryForList("""
                    SELECT customer_id FROM customer_asset_relation
                    WHERE asset_id=:assetId AND status='ACTIVE'
                    ORDER BY primary_relation DESC, created_at LIMIT 1
                    """, Map.of("assetId", request.assetId()), String.class);
            customerId = customers.isEmpty() ? null : customers.get(0);
        }
        List<String> definitionIds = request.lines().stream().map(FeeModels.TemporaryLine::feeDefinitionId).distinct().toList();
        List<Map<String, Object>> definitionRows = jdbc.queryForList("""
                SELECT * FROM fee_definition
                WHERE community_id=:communityId AND id IN (:ids) AND enabled=TRUE
                """, new MapSqlParameterSource("communityId", request.communityId()).addValue("ids", definitionIds));
        Map<String, Map<String, Object>> definitions = definitionRows.stream().collect(Collectors.toMap(
                row -> text(row.get("id")), row -> row));
        List<FeeModels.PreviewLine> lines = new ArrayList<>();
        List<FeeModels.PreviewError> errors = new ArrayList<>();
        for (int index = 0; index < request.lines().size(); index++) {
            int rowNo = index + 1;
            FeeModels.TemporaryLine input = request.lines().get(index);
            Map<String, Object> definition = definitions.get(input.feeDefinitionId());
            if (definition == null || !booleanValue(definition.get("temporary_allowed"))) {
                errors.add(new FeeModels.PreviewError(rowNo, request.assetId(), null,
                        "TEMPORARY_FEE_NOT_ALLOWED", "费用定义不存在、已停用或不允许临时应收"));
                continue;
            }
            BigDecimal raw = input.quantity().multiply(input.unitPrice()).multiply(input.coefficient());
            int scale = ((Number) definition.get("decimal_scale")).intValue();
            RoundingMode mode = RoundingMode.valueOf(text(definition.get("rounding_mode")));
            BigDecimal amount = raw.setScale(scale, mode);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schemaVersion", 1);
            snapshot.put("jobType", "TEMPORARY");
            snapshot.put("billingPeriod", YearMonth.from(request.chargeDate()).toString());
            snapshot.put("chargeDate", request.chargeDate());
            snapshot.put("dueDate", request.dueDate());
            snapshot.put("assetId", request.assetId());
            snapshot.put("assetName", text(assets.get(0).get("display_name")));
            snapshot.put("customerId", customerId);
            snapshot.put("feeDefinitionId", input.feeDefinitionId());
            snapshot.put("feeDefinitionCode", definition.get("code"));
            snapshot.put("feeDefinitionName", definition.get("name"));
            snapshot.put("feeDefinitionVersion", definition.get("version"));
            snapshot.put("feeStandardId", null);
            snapshot.put("feeStandardVersionId", null);
            snapshot.put("feeAllocationId", null);
            snapshot.put("itemName", input.itemName());
            snapshot.put("calculationBasis", "MANUAL_QUANTITY");
            snapshot.put("formulaCode", "quantity * unitPrice * coefficient");
            snapshot.put("quantity", input.quantity());
            snapshot.put("unitPrice", input.unitPrice());
            snapshot.put("coefficient", input.coefficient());
            snapshot.put("rawAmount", raw);
            snapshot.put("boundedAmount", raw);
            snapshot.put("decimalScale", scale);
            snapshot.put("roundingMode", mode.name());
            snapshot.put("currencyCode", definition.get("currency_code"));
            snapshot.put("taxCategoryCode", definition.get("tax_category_code"));
            snapshot.put("taxRate", definition.get("tax_rate"));
            snapshot.put("amount", amount);
            snapshot.put("assumptionRule", false);
            String checksum = sha256(json(snapshot));
            snapshot.put("configurationChecksum", checksum);
            lines.add(new FeeModels.PreviewLine(rowNo, "ASSET", request.assetId(), request.assetId(),
                    text(assets.get(0).get("display_name")), input.itemName(), input.quantity(),
                    input.unitPrice(), input.coefficient(), raw, amount, checksum, snapshot));
        }
        return preview("TEMPORARY", YearMonth.from(request.chargeDate()).toString(),
                request.chargeDate(), lines, errors);
    }

    private FeeModels.PreviewLine lineFromConfiguration(int rowNo, String period, LocalDate chargeDate,
                                                        Map<String, Object> row, BigDecimal quantity) {
        BigDecimal unitPrice = decimal(row.get("unit_price"));
        BigDecimal coefficient = decimal(row.get("coefficient"));
        BigDecimal raw = quantity.multiply(unitPrice).multiply(coefficient);
        BigDecimal bounded = raw;
        if (row.get("minimum_amount") != null) bounded = bounded.max(decimal(row.get("minimum_amount")));
        if (row.get("maximum_amount") != null) bounded = bounded.min(decimal(row.get("maximum_amount")));
        int scale = ((Number) row.get("decimal_scale")).intValue();
        RoundingMode roundingMode = RoundingMode.valueOf(text(row.get("rounding_mode")));
        BigDecimal amount = bounded.setScale(scale, roundingMode);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("jobType", "PERIODIC");
        snapshot.put("billingPeriod", period);
        snapshot.put("chargeDate", chargeDate);
        snapshot.put("dueDate", YearMonth.parse(period).atEndOfMonth());
        snapshot.put("targetType", row.get("target_type"));
        snapshot.put("targetId", row.get("target_id"));
        snapshot.put("assetId", row.get("asset_id"));
        snapshot.put("assetName", row.get("asset_name"));
        snapshot.put("customerId", row.get("customer_id"));
        snapshot.put("feeDefinitionId", row.get("definition_id"));
        snapshot.put("feeDefinitionCode", row.get("definition_code"));
        snapshot.put("feeDefinitionName", row.get("definition_name"));
        snapshot.put("feeDefinitionVersion", row.get("definition_version"));
        snapshot.put("feeStandardId", row.get("standard_id"));
        snapshot.put("feeStandardCode", row.get("standard_code"));
        snapshot.put("feeStandardVersion", row.get("standard_version"));
        snapshot.put("feeStandardVersionId", row.get("standard_version_id"));
        snapshot.put("feeStandardVersionNo", row.get("version_no"));
        snapshot.put("standardEffectiveFrom", row.get("version_from"));
        snapshot.put("standardEffectiveTo", row.get("version_to"));
        snapshot.put("feeAllocationId", row.get("allocation_id"));
        snapshot.put("feeAllocationVersion", row.get("allocation_version"));
        snapshot.put("allocationEffectiveFrom", row.get("allocation_from"));
        snapshot.put("allocationEffectiveTo", row.get("allocation_to"));
        snapshot.put("allocationSourceType", row.get("allocation_source"));
        snapshot.put("itemName", row.get("definition_name"));
        snapshot.put("calculationBasis", row.get("calculation_basis"));
        snapshot.put("prorationRule", row.get("proration_rule"));
        snapshot.put("formulaCode", row.get("formula_code"));
        snapshot.put("formulaExpression", row.get("formula_expression"));
        snapshot.put("quantity", quantity);
        snapshot.put("unitPrice", unitPrice);
        snapshot.put("coefficient", coefficient);
        snapshot.put("minimumAmount", row.get("minimum_amount"));
        snapshot.put("maximumAmount", row.get("maximum_amount"));
        snapshot.put("rawAmount", raw);
        snapshot.put("boundedAmount", bounded);
        snapshot.put("decimalScale", scale);
        snapshot.put("roundingMode", roundingMode.name());
        snapshot.put("currencyCode", row.get("currency_code"));
        snapshot.put("taxCategoryCode", row.get("tax_category_code"));
        snapshot.put("taxRate", row.get("tax_rate"));
        snapshot.put("amount", amount);
        snapshot.put("assumptionRule", false);
        String checksum = sha256(json(snapshot));
        snapshot.put("configurationChecksum", checksum);
        return new FeeModels.PreviewLine(rowNo, text(row.get("target_type")), text(row.get("target_id")),
                text(row.get("asset_id")), text(row.get("asset_name")), text(row.get("definition_name")),
                quantity, unitPrice, coefficient, raw, amount, checksum, snapshot);
    }

    private FeeModels.PreviewResult preview(String jobType, String period, LocalDate chargeDate,
                                            List<FeeModels.PreviewLine> lines,
                                            List<FeeModels.PreviewError> errors) {
        BigDecimal total = lines.stream().map(FeeModels.PreviewLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String checksums = lines.stream().map(FeeModels.PreviewLine::configurationChecksum).sorted()
                .collect(Collectors.joining("|"));
        return new FeeModels.PreviewResult(jobType, period, chargeDate, lines.size(), errors.size(), total,
                sha256(checksums), lines, errors);
    }

    private FeeModels.JobReceipt replay(Map<String, Object> row, String requestHash) {
        if (!requestHash.equals(text(row.get("request_hash")))) {
            throw conflict("IDEMPOTENCY_REQUEST_CONFLICT", "相同 Idempotency-Key 已用于不同请求内容");
        }
        return new FeeModels.JobReceipt(text(row.get("id")), text(row.get("job_type")), text(row.get("status")),
                integer(row.get("requested_count")), integer(row.get("generated_count")),
                integer(row.get("skipped_count")), integer(row.get("error_count")),
                decimal(row.get("total_amount")), true);
    }

    private void insertError(String jobId, Integer rowNo, String assetId, String standardId,
                             String code, String message, Object detail, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO receivable_generation_error
                    (id, job_id, row_no, asset_id, fee_standard_id, error_code,
                     error_message, detail_json, created_at)
                VALUES (:id, :jobId, :rowNo, :assetId, :standardId, :code, :message, :detail, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("jobId", jobId)
                .addValue("rowNo", rowNo).addValue("assetId", assetId).addValue("standardId", standardId)
                .addValue("code", code).addValue("message", safe(message)).addValue("detail", json(detail))
                .addValue("now", now));
    }

    private void scheduleAfterCommit(String jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    processor.process(jobId);
                }
            });
        } else {
            processor.process(jobId);
        }
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "生成应收必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
        String normalized = value.trim();
        if (normalized.length() > 120) throw invalid("Idempotency-Key 最长为 120 个字符");
        return normalized;
    }

    private YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (RuntimeException exception) {
            throw invalid("账期必须使用 YYYY-MM 格式");
        }
    }

    private List<String> normalizedIds(List<String> ids) {
        return ids.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize receivable request", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safe(String value) {
        String text = value == null ? "generation failed" : value;
        return text.substring(0, Math.min(480, text.length()));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int integer(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : value instanceof Number number && number.intValue() != 0;
    }

    private void requireRead(String communityId) {
        security.requirePermission("fee:read");
        security.requireProject(communityId);
    }

    private void requireWrite(String communityId) {
        security.requirePermission("fee:write");
        security.requireProject(communityId);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_RECEIVABLE_REQUEST", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("RECEIVABLE_RESOURCE_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
