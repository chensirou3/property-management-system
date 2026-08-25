package com.propertyops.pms.fee;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class FeeConfigurationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public FeeConfigurationService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                                   AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> definitions(String communityId) {
        requireRead(communityId);
        return jdbc.queryForList("""
                SELECT id, community_id communityId, code, name, fee_type feeType, fee_class feeClass,
                       unit_code unitCode, decimal_scale decimalScale, rounding_mode roundingMode,
                       currency_code currencyCode, late_fee_enabled lateFeeEnabled,
                       temporary_allowed temporaryAllowed, accounting_subject_code accountingSubjectCode,
                       prepayment_subject_code prepaymentSubjectCode, tax_category_code taxCategoryCode,
                       tax_rate taxRate, enabled, version, created_at createdAt, updated_at updatedAt
                FROM fee_definition WHERE community_id=:communityId ORDER BY enabled DESC, code
                """, Map.of("communityId", communityId));
    }

    public List<Map<String, Object>> standards(String communityId, String definitionId) {
        requireRead(communityId);
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId);
        String predicate = "";
        if (definitionId != null && !definitionId.isBlank()) {
            predicate = " AND fs.fee_definition_id=:definitionId";
            params.addValue("definitionId", definitionId);
        }
        return jdbc.queryForList("""
                SELECT fs.id, fs.community_id communityId, fs.fee_definition_id feeDefinitionId,
                       fd.code feeDefinitionCode, fd.name feeDefinitionName, fs.code, fs.name,
                       fs.asset_type assetType, fs.billing_cycle billingCycle,
                       fs.calculation_basis calculationBasis, fs.proration_rule prorationRule,
                       fs.status, fs.version,
                       fsv.id currentVersionId, fsv.version_no currentVersionNo,
                       fsv.unit_price unitPrice, fsv.minimum_amount minimumAmount,
                       fsv.maximum_amount maximumAmount, fsv.formula_code formulaCode,
                       fsv.formula_expression formulaExpression,
                       fsv.effective_from effectiveFrom, fsv.effective_to effectiveTo
                FROM fee_standard fs
                JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                LEFT JOIN fee_standard_version fsv ON fsv.id=(
                    SELECT v.id FROM fee_standard_version v WHERE v.fee_standard_id=fs.id
                    ORDER BY v.version_no DESC LIMIT 1)
                WHERE fs.community_id=:communityId
                """ + predicate + " ORDER BY fs.status, fs.code", params);
    }

    public List<Map<String, Object>> versions(String standardId, String communityId) {
        requireRead(communityId);
        requireStandard(communityId, standardId, false);
        return jdbc.queryForList("""
                SELECT id, fee_standard_id feeStandardId, version_no versionNo, unit_price unitPrice,
                       minimum_amount minimumAmount, maximum_amount maximumAmount,
                       formula_code formulaCode, formula_expression formulaExpression,
                       effective_from effectiveFrom, effective_to effectiveTo, status,
                       created_by createdBy, published_at publishedAt, created_at createdAt
                FROM fee_standard_version WHERE fee_standard_id=:standardId ORDER BY version_no DESC
                """, Map.of("standardId", standardId));
    }

    public List<Map<String, Object>> allocations(String communityId, String standardId) {
        requireRead(communityId);
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId);
        String predicate = "";
        if (standardId != null && !standardId.isBlank()) {
            predicate = " AND fa.fee_standard_id=:standardId";
            params.addValue("standardId", standardId);
        }
        return jdbc.queryForList("""
                SELECT fa.id, fa.fee_standard_id feeStandardId, fs.code standardCode, fs.name standardName,
                       fa.target_type targetType, fa.target_identity targetId,
                       COALESCE(a.display_name, m.meter_no) targetName,
                       fa.asset_id assetId, fa.meter_id meterId, fa.coefficient,
                       fa.effective_from effectiveFrom, fa.effective_to effectiveTo,
                       fa.source_type sourceType, fa.cancellation_reason cancellationReason,
                       fa.status, fa.version, fa.created_at createdAt, fa.updated_at updatedAt
                FROM fee_allocation fa
                JOIN fee_standard fs ON fs.id=fa.fee_standard_id
                LEFT JOIN asset a ON a.id=fa.asset_id
                LEFT JOIN meter m ON m.id=fa.meter_id
                WHERE fa.community_id=:communityId
                """ + predicate + " ORDER BY fa.created_at DESC, fa.id", params);
    }

    @Transactional
    public Map<String, Object> createDefinition(FeeModels.CreateDefinition request) {
        requireWrite(request.communityId());
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        try {
            jdbc.update("""
                    INSERT INTO fee_definition
                        (id, community_id, code, name, fee_type, fee_class, unit_code, decimal_scale,
                         late_fee_enabled, enabled, accounting_subject_code, prepayment_subject_code,
                         tax_category_code, tax_rate, rounding_mode, currency_code, temporary_allowed,
                         version, created_at, updated_at)
                    VALUES (:id, :communityId, :code, :name, :feeType, :feeClass, :unitCode, :decimalScale,
                            :lateFee, TRUE, :accounting, :prepayment, :taxCategory, :taxRate,
                            :roundingMode, :currencyCode, :temporaryAllowed, 0, :now, :now)
                    """, definitionParams(request, id, now));
        } catch (DuplicateKeyException exception) {
            throw conflict("FEE_DEFINITION_CODE_CONFLICT", "费用定义编码已存在");
        }
        event(request.communityId(), "DEFINITION", id, "CREATED", null, 0L, request);
        audit.success(request.communityId(), "fee-definition:create", "fee-definition", id,
                Map.of("code", request.code(), "roundingMode", request.roundingMode()));
        return definition(id, request.communityId());
    }

    @Transactional
    public Map<String, Object> updateDefinition(String id, String communityId, FeeModels.UpdateDefinition request) {
        requireWrite(communityId);
        Map<String, Object> current = lockDefinition(id, communityId);
        long version = ((Number) current.get("version")).longValue();
        if (version != request.expectedVersion()) throw optimistic();
        LocalDateTime now = now();
        int changed = jdbc.update("""
                UPDATE fee_definition
                SET name=:name, fee_type=:feeType, fee_class=:feeClass, unit_code=:unitCode,
                    decimal_scale=:decimalScale, late_fee_enabled=:lateFee,
                    temporary_allowed=:temporaryAllowed, accounting_subject_code=:accounting,
                    prepayment_subject_code=:prepayment, tax_category_code=:taxCategory,
                    tax_rate=:taxRate, rounding_mode=:roundingMode, currency_code=:currencyCode,
                    enabled=:enabled, version=version+1, updated_at=:now
                WHERE id=:id AND community_id=:communityId AND version=:expectedVersion
                """, new MapSqlParameterSource("id", id).addValue("communityId", communityId)
                .addValue("name", request.name()).addValue("feeType", request.feeType())
                .addValue("feeClass", request.feeClass()).addValue("unitCode", request.unitCode())
                .addValue("decimalScale", request.decimalScale()).addValue("lateFee", request.lateFeeEnabled())
                .addValue("temporaryAllowed", request.temporaryAllowed()).addValue("accounting", request.accountingSubjectCode())
                .addValue("prepayment", request.prepaymentSubjectCode()).addValue("taxCategory", request.taxCategoryCode())
                .addValue("taxRate", request.taxRate()).addValue("roundingMode", request.roundingMode())
                .addValue("currencyCode", request.currencyCode()).addValue("enabled", request.enabled())
                .addValue("now", now).addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) throw optimistic();
        event(communityId, "DEFINITION", id, "UPDATED", null, version + 1, request);
        audit.success(communityId, "fee-definition:update", "fee-definition", id,
                Map.of("previousVersion", version, "newVersion", version + 1));
        return definition(id, communityId);
    }

    @Transactional
    public Map<String, Object> createStandard(FeeModels.CreateStandard request) {
        requireWrite(request.communityId());
        validateDates(request.effectiveFrom(), request.effectiveTo());
        validateBounds(request.minimumAmount(), request.maximumAmount());
        Map<String, Object> definition = lockDefinition(request.feeDefinitionId(), request.communityId());
        if (!booleanValue(definition.get("enabled"))) throw invalid("费用定义已停用");
        validateBasis(request.assetType(), request.calculationBasis());
        String id = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        try {
            jdbc.update("""
                    INSERT INTO fee_standard
                        (id, community_id, fee_definition_id, code, name, asset_type, billing_cycle,
                         calculation_basis, proration_rule, status, version, created_at, updated_at)
                    VALUES (:id, :communityId, :definitionId, :code, :name, :assetType, :billingCycle,
                            :basis, :proration, 'ACTIVE', 0, :now, :now)
                    """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                    .addValue("definitionId", request.feeDefinitionId()).addValue("code", request.code())
                    .addValue("name", request.name()).addValue("assetType", request.assetType())
                    .addValue("billingCycle", request.billingCycle()).addValue("basis", request.calculationBasis())
                    .addValue("proration", request.prorationRule()).addValue("now", now));
        } catch (DuplicateKeyException exception) {
            throw conflict("FEE_STANDARD_CODE_CONFLICT", "同一费用定义下的标准编码已存在");
        }
        insertVersion(id, versionId, 1, request.unitPrice(), request.minimumAmount(), request.maximumAmount(),
                request.formulaCode(), request.formulaExpression(), request.effectiveFrom(), request.effectiveTo(), now);
        event(request.communityId(), "STANDARD", id, "CREATED", request.effectiveFrom(), 0L,
                Map.of("versionId", versionId, "calculationBasis", request.calculationBasis()));
        event(request.communityId(), "VERSION", versionId, "PUBLISHED", request.effectiveFrom(), 1L, request);
        audit.success(request.communityId(), "fee-standard:create", "fee-standard", id,
                Map.of("versionId", versionId, "versionNo", 1));
        return standard(id, request.communityId());
    }

    @Transactional
    public Map<String, Object> addVersion(String standardId, FeeModels.CreateStandardVersion request) {
        requireWrite(request.communityId());
        validateDates(request.effectiveFrom(), request.effectiveTo());
        validateBounds(request.minimumAmount(), request.maximumAmount());
        Map<String, Object> standard = requireStandard(request.communityId(), standardId, true);
        if (!"ACTIVE".equals(String.valueOf(standard.get("status")))) throw invalid("费用标准已停用");
        assertNoVersionOverlap(standardId, request.effectiveFrom(), request.effectiveTo());
        Integer latest = jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM fee_standard_version WHERE fee_standard_id=:id",
                Map.of("id", standardId), Integer.class);
        int versionNo = (latest == null ? 0 : latest) + 1;
        String id = UUID.randomUUID().toString();
        insertVersion(standardId, id, versionNo, request.unitPrice(), request.minimumAmount(), request.maximumAmount(),
                request.formulaCode(), request.formulaExpression(), request.effectiveFrom(), request.effectiveTo(), now());
        jdbc.update("UPDATE fee_standard SET version=version+1, updated_at=:now WHERE id=:id",
                Map.of("now", now(), "id", standardId));
        event(request.communityId(), "VERSION", id, "PUBLISHED", request.effectiveFrom(), (long) versionNo, request);
        audit.success(request.communityId(), "fee-standard-version:create", "fee-standard-version", id,
                Map.of("standardId", standardId, "versionNo", versionNo));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("feeStandardId", standardId);
        result.put("versionNo", versionNo);
        result.put("status", "ACTIVE");
        return result;
    }

    @Transactional
    public Map<String, Object> disableStandard(String standardId, String communityId) {
        requireWrite(communityId);
        Map<String, Object> standard = requireStandard(communityId, standardId, true);
        if ("INACTIVE".equals(String.valueOf(standard.get("status")))) {
            return Map.of("id", standardId, "status", "INACTIVE", "replayed", true);
        }
        LocalDateTime now = now();
        jdbc.update("UPDATE fee_standard SET status='INACTIVE', version=version+1, updated_at=:now WHERE id=:id",
                Map.of("now", now, "id", standardId));
        event(communityId, "STANDARD", standardId, "DISABLED", LocalDate.now(ZoneOffset.UTC),
                ((Number) standard.get("version")).longValue() + 1, Map.of("reason", "manual-disable"));
        audit.success(communityId, "fee-standard:disable", "fee-standard", standardId, Map.of());
        return Map.of("id", standardId, "status", "INACTIVE", "replayed", false);
    }

    public FeeModels.BatchResult previewAllocations(FeeModels.AllocationRequest request) {
        requireWrite(request.communityId());
        return inspectAllocations(request, false);
    }

    @Transactional
    public FeeModels.BatchResult assignAllocations(FeeModels.AllocationRequest request) {
        requireWrite(request.communityId());
        FeeModels.BatchResult result = inspectAllocations(request, true);
        audit.success(request.communityId(), "fee-allocation:assign", "fee-standard", request.feeStandardId(),
                Map.of("assigned", result.changed(), "skipped", result.skipped()));
        return result;
    }

    @Transactional
    public FeeModels.BatchResult cancelAllocations(FeeModels.CancelAllocations request) {
        requireWrite(request.communityId());
        List<Map<String, Object>> items = new ArrayList<>();
        int changed = 0;
        int skipped = 0;
        for (String id : request.allocationIds().stream().distinct().toList()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT * FROM fee_allocation WHERE id=:id AND community_id=:communityId FOR UPDATE
                    """, Map.of("id", id, "communityId", request.communityId()));
            if (rows.isEmpty()) {
                skipped++;
                items.add(item(id, "NOT_FOUND", "分配记录不存在或不属于当前项目"));
                continue;
            }
            Map<String, Object> row = rows.get(0);
            LocalDate from = localDate(row.get("effective_from"));
            if (request.effectiveTo().isBefore(from)) throw invalid("失效日期不能早于生效日期");
            LocalDate priorTo = row.get("effective_to") == null ? null : localDate(row.get("effective_to"));
            if (priorTo != null && !request.effectiveTo().isBefore(priorTo)
                    && request.reason().equals(String.valueOf(row.get("cancellation_reason")))) {
                skipped++;
                items.add(item(id, "REPLAYED", "相同失效操作已执行"));
                continue;
            }
            LocalDate effectiveTo = priorTo == null || request.effectiveTo().isBefore(priorTo)
                    ? request.effectiveTo() : priorTo;
            jdbc.update("""
                    UPDATE fee_allocation
                    SET effective_to=:effectiveTo, cancellation_reason=:reason,
                        version=version+1, updated_at=:now
                    WHERE id=:id
                    """, Map.of("effectiveTo", effectiveTo, "reason", request.reason(), "now", now(), "id", id));
            changed++;
            items.add(item(id, "CANCELLED", "有效期已截止"));
            event(request.communityId(), "ALLOCATION", id, "CANCELLED", effectiveTo,
                    ((Number) row.get("version")).longValue() + 1, Map.of("reason", request.reason()));
        }
        audit.success(request.communityId(), "fee-allocation:cancel", "fee-allocation", null,
                Map.of("changed", changed, "skipped", skipped, "effectiveTo", request.effectiveTo()));
        return new FeeModels.BatchResult(changed, skipped, items);
    }

    private FeeModels.BatchResult inspectAllocations(FeeModels.AllocationRequest request, boolean persist) {
        validateDates(request.effectiveFrom(), request.effectiveTo());
        Map<String, Object> standard = requireStandard(request.communityId(), request.feeStandardId(), persist);
        if (!"ACTIVE".equals(String.valueOf(standard.get("status")))) throw invalid("费用标准已停用");
        validateTargetType(String.valueOf(standard.get("asset_type")), request.targetType());
        List<Map<String, Object>> items = new ArrayList<>();
        int changed = 0;
        int skipped = 0;
        for (String targetId : request.targetIds().stream().distinct().toList()) {
            Map<String, Object> target = requireTarget(request.communityId(), request.targetType(), targetId,
                    String.valueOf(standard.get("asset_type")));
            List<Map<String, Object>> overlap = jdbc.queryForList("""
                    SELECT id, coefficient, effective_from, effective_to, status
                    FROM fee_allocation
                    WHERE fee_standard_id=:standardId AND target_type=:targetType
                      AND target_identity=:targetId
                      AND NOT (COALESCE(effective_to, DATE('9999-12-31')) < :effectiveFrom
                               OR effective_from > COALESCE(:effectiveTo, DATE('9999-12-31')))
                    FOR UPDATE
                    """, new MapSqlParameterSource("standardId", request.feeStandardId())
                    .addValue("targetType", request.targetType()).addValue("targetId", targetId)
                    .addValue("effectiveFrom", request.effectiveFrom()).addValue("effectiveTo", request.effectiveTo()));
            if (!overlap.isEmpty()) {
                Map<String, Object> prior = overlap.get(0);
                boolean exact = request.effectiveFrom().equals(localDate(prior.get("effective_from")))
                        && equalDate(request.effectiveTo(), prior.get("effective_to"))
                        && request.coefficient().compareTo(decimal(prior.get("coefficient"))) == 0;
                if (overlap.size() == 1 && exact) {
                    skipped++;
                    items.add(item(String.valueOf(prior.get("id")), "REPLAYED", "相同分配已存在"));
                    continue;
                }
                throw conflict("FEE_ALLOCATION_OVERLAP", "同一费用标准与对象的有效期不能重叠：" + targetId);
            }
            if (!persist) {
                changed++;
                items.add(item(targetId, "READY", String.valueOf(target.get("targetName"))));
                continue;
            }
            String id = UUID.randomUUID().toString();
            LocalDateTime now = now();
            jdbc.update("""
                    INSERT INTO fee_allocation
                        (id, community_id, fee_standard_id, target_type, asset_id, meter_id,
                         coefficient, source_type, effective_from, effective_to, status,
                         version, created_at, updated_at)
                    VALUES (:id, :communityId, :standardId, :targetType, :assetId, :meterId,
                            :coefficient, :sourceType, :effectiveFrom, :effectiveTo, 'ACTIVE', 0, :now, :now)
                    """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                    .addValue("standardId", request.feeStandardId()).addValue("targetType", request.targetType())
                    .addValue("assetId", "ASSET".equals(request.targetType()) ? targetId : null)
                    .addValue("meterId", "METER".equals(request.targetType()) ? targetId : null)
                    .addValue("coefficient", request.coefficient()).addValue("sourceType", request.sourceType())
                    .addValue("effectiveFrom", request.effectiveFrom()).addValue("effectiveTo", request.effectiveTo())
                    .addValue("now", now));
            changed++;
            items.add(item(id, "ASSIGNED", String.valueOf(target.get("targetName"))));
            event(request.communityId(), "ALLOCATION", id, "ASSIGNED", request.effectiveFrom(), 0L,
                    Map.of("standardId", request.feeStandardId(), "targetType", request.targetType(), "targetId", targetId));
        }
        return new FeeModels.BatchResult(changed, skipped, items);
    }

    private MapSqlParameterSource definitionParams(FeeModels.CreateDefinition request, String id, LocalDateTime now) {
        return new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("code", request.code()).addValue("name", request.name())
                .addValue("feeType", request.feeType()).addValue("feeClass", request.feeClass())
                .addValue("unitCode", request.unitCode()).addValue("decimalScale", request.decimalScale())
                .addValue("lateFee", request.lateFeeEnabled()).addValue("temporaryAllowed", request.temporaryAllowed())
                .addValue("accounting", request.accountingSubjectCode()).addValue("prepayment", request.prepaymentSubjectCode())
                .addValue("taxCategory", request.taxCategoryCode()).addValue("taxRate", request.taxRate())
                .addValue("roundingMode", request.roundingMode()).addValue("currencyCode", request.currencyCode())
                .addValue("now", now);
    }

    private void insertVersion(String standardId, String id, int versionNo, java.math.BigDecimal unitPrice,
                               java.math.BigDecimal minimumAmount, java.math.BigDecimal maximumAmount,
                               String formulaCode, String expression, LocalDate from, LocalDate to, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO fee_standard_version
                    (id, fee_standard_id, version_no, unit_price, minimum_amount, maximum_amount,
                     formula_code, formula_expression, effective_from, effective_to, status,
                     created_by, published_at, created_at)
                VALUES (:id, :standardId, :versionNo, :unitPrice, :minimumAmount, :maximumAmount,
                        :formulaCode, :expression, :effectiveFrom, :effectiveTo, 'ACTIVE',
                        :actor, :now, :now)
                """, new MapSqlParameterSource("id", id).addValue("standardId", standardId)
                .addValue("versionNo", versionNo).addValue("unitPrice", unitPrice)
                .addValue("minimumAmount", minimumAmount).addValue("maximumAmount", maximumAmount)
                .addValue("formulaCode", formulaCode).addValue("expression", expression)
                .addValue("effectiveFrom", from).addValue("effectiveTo", to)
                .addValue("actor", security.requirePrincipal().userId()).addValue("now", now));
    }

    private void assertNoVersionOverlap(String standardId, LocalDate from, LocalDate to) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_standard_version
                WHERE fee_standard_id=:standardId AND status='ACTIVE'
                  AND NOT (COALESCE(effective_to, DATE('9999-12-31')) < :effectiveFrom
                           OR effective_from > COALESCE(:effectiveTo, DATE('9999-12-31')))
                """, new MapSqlParameterSource("standardId", standardId).addValue("effectiveFrom", from)
                .addValue("effectiveTo", to), Long.class);
        if (count != null && count > 0) throw conflict("FEE_STANDARD_VERSION_OVERLAP", "费用标准版本有效期不能重叠");
    }

    private Map<String, Object> definition(String id, String communityId) {
        return jdbc.queryForMap("SELECT * FROM fee_definition WHERE id=:id AND community_id=:communityId",
                Map.of("id", id, "communityId", communityId));
    }

    private Map<String, Object> standard(String id, String communityId) {
        return jdbc.queryForMap("SELECT * FROM fee_standard WHERE id=:id AND community_id=:communityId",
                Map.of("id", id, "communityId", communityId));
    }

    private Map<String, Object> lockDefinition(String id, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM fee_definition WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("费用定义不存在");
        return rows.get(0);
    }

    private Map<String, Object> requireStandard(String communityId, String id, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM fee_standard WHERE id=:id AND community_id=:communityId
                """ + suffix, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("费用标准不存在");
        return rows.get(0);
    }

    private Map<String, Object> requireTarget(String communityId, String targetType, String targetId, String assetType) {
        if ("ASSET".equals(targetType)) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id targetId, display_name targetName, asset_type assetType
                    FROM asset WHERE id=:id AND community_id=:communityId AND enabled=TRUE
                    """, Map.of("id", targetId, "communityId", communityId));
            if (rows.isEmpty()) throw notFound("分配资产不存在或已停用：" + targetId);
            if (!assetType.equals(String.valueOf(rows.get(0).get("assetType")))) throw invalid("资产类型与费用标准不匹配");
            return rows.get(0);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id targetId, meter_no targetName FROM meter
                WHERE id=:id AND community_id=:communityId AND status='ACTIVE'
                """, Map.of("id", targetId, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("分配仪表不存在或已停用：" + targetId);
        return rows.get(0);
    }

    private void validateBasis(String assetType, String basis) {
        if ("METER_USAGE".equals(basis) != "METER".equals(assetType)) {
            throw invalid("计量用量计费只能用于仪表标准，仪表标准也必须使用计量用量");
        }
    }

    private void validateTargetType(String assetType, String targetType) {
        if ("METER".equals(assetType) != "METER".equals(targetType)) throw invalid("分配对象类型与费用标准不匹配");
    }

    private void validateDates(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) throw invalid("结束日期不能早于开始日期");
    }

    private void validateBounds(java.math.BigDecimal minimum, java.math.BigDecimal maximum) {
        if (minimum != null && maximum != null && maximum.compareTo(minimum) < 0) {
            throw invalid("最高金额不能低于最低金额");
        }
    }

    private boolean equalDate(LocalDate expected, Object actual) {
        return expected == null ? actual == null : actual != null && expected.equals(localDate(actual));
    }

    private LocalDate localDate(Object value) {
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof LocalDate date) return date;
        return LocalDate.parse(String.valueOf(value));
    }

    private java.math.BigDecimal decimal(Object value) {
        return value instanceof java.math.BigDecimal decimal ? decimal : new java.math.BigDecimal(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : value instanceof Number number && number.intValue() != 0;
    }

    private Map<String, Object> item(String id, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("status", status);
        result.put("message", message);
        return result;
    }

    private void event(String communityId, String aggregateType, String aggregateId, String eventType,
                       LocalDate effectiveDate, Long newVersion, Object detail) {
        jdbc.update("""
                INSERT INTO fee_configuration_event
                    (id, community_id, aggregate_type, aggregate_id, event_type, effective_date,
                     previous_version, new_version, detail_json, actor_user_id, created_at)
                VALUES (:id, :communityId, :aggregateType, :aggregateId, :eventType, :effectiveDate,
                        NULL, :newVersion, :detail, :actor, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("communityId", communityId)
                .addValue("aggregateType", aggregateType).addValue("aggregateId", aggregateId)
                .addValue("eventType", eventType).addValue("effectiveDate", effectiveDate)
                .addValue("newVersion", newVersion).addValue("detail", json(detail))
                .addValue("actor", security.requirePrincipal().userId()).addValue("now", now()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize fee configuration event", exception);
        }
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
        return new BusinessException("INVALID_FEE_CONFIGURATION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("FEE_CONFIGURATION_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private BusinessException optimistic() {
        return conflict("OPTIMISTIC_LOCK_CONFLICT", "记录已被其他操作修改，请刷新后重试");
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }
}
