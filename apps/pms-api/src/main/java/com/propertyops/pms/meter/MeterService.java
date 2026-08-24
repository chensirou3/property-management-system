package com.propertyops.pms.meter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.adapter.IotAdapter;
import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class MeterService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final IotAdapter iotAdapter;

    public MeterService(NamedParameterJdbcTemplate jdbc, SecurityContextService security, AuditService audit,
                        ObjectMapper objectMapper, IotAdapter iotAdapter) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.iotAdapter = iotAdapter;
    }

    @Transactional
    public Map<String, Object> createBatch(CreateBatch request) {
        security.requirePermission("meter:write");
        security.requireProject(request.communityId());
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT * FROM meter_reading_batch WHERE community_id=:communityId AND batch_no=:batchNo
                """, Map.of("communityId", request.communityId(), "batchNo", request.batchNo()));
        if (!existing.isEmpty()) {
            Map<String, Object> prior = existing.get(0);
            if (!request.readingPeriod().equals(String.valueOf(prior.get("reading_period")))
                    || !request.sourceType().equals(String.valueOf(prior.get("source_type")))) {
                throw new BusinessException("METER_BATCH_CONFLICT", "相同批次号已用于不同周期或来源", HttpStatus.CONFLICT);
            }
            return prior;
        }
        YearMonth.parse(request.readingPeriod());
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO meter_reading_batch
                    (id, community_id, batch_no, reading_period, status, source_type, version, created_at, updated_at)
                VALUES (:id, :communityId, :batchNo, :period, 'DRAFT', :sourceType, 0, :now, :now)
                """, Map.of("id", id, "communityId", request.communityId(), "batchNo", request.batchNo(),
                "period", request.readingPeriod(), "sourceType", request.sourceType(), "now", now));
        audit.success(request.communityId(), "meter-batch:create", "meter-reading-batch", id,
                Map.of("period", request.readingPeriod(), "sourceType", request.sourceType()));
        return jdbc.queryForMap("SELECT * FROM meter_reading_batch WHERE id=:id", Map.of("id", id));
    }

    @Transactional
    public InputResult input(ReadingsInput request) {
        security.requirePermission("meter:write");
        security.requireProject(request.communityId());
        Map<String, Object> batch = lockBatch(request.batchId(), request.communityId());
        if (!"DRAFT".equals(batch.get("status"))) throw invalid("只有草稿批次可以录入读数");
        int created = 0;
        int replayed = 0;
        for (ReadingInput input : request.readings()) {
            Map<String, Object> meter = requireMeter(request.communityId(), input.meterId());
            List<Map<String, Object>> prior = jdbc.queryForList("""
                    SELECT id, current_reading FROM meter_reading
                    WHERE meter_id=:meterId AND batch_id=:batchId
                    """, Map.of("meterId", input.meterId(), "batchId", request.batchId()));
            if (!prior.isEmpty()) {
                if (decimal(prior.get(0).get("current_reading")).compareTo(input.currentReading()) == 0) replayed++;
                else throw new BusinessException("READING_ALREADY_EXISTS", "该批次已录入此仪表，且读数不同", HttpStatus.CONFLICT);
                continue;
            }
            BigDecimal previous = input.previousReading();
            if (previous == null) {
                List<BigDecimal> history = jdbc.queryForList("""
                        SELECT current_reading FROM meter_reading
                        WHERE meter_id=:meterId AND status='APPROVED'
                        ORDER BY reading_at DESC LIMIT 1
                        """, Map.of("meterId", input.meterId()), BigDecimal.class);
                previous = history.isEmpty() ? BigDecimal.ZERO : history.get(0);
            }
            BigDecimal correction = input.correction() == null ? decimal(meter.get("correction")) : input.correction();
            BigDecimal share = input.allocatedShare() == null ? BigDecimal.ZERO : input.allocatedShare();
            BigDecimal multiplier = decimal(meter.get("multiplier"));
            MeterUsageCalculator.Usage usage = MeterUsageCalculator.calculate(previous, input.currentReading(),
                    multiplier, correction, share);
            String id = UUID.randomUUID().toString();
            LocalDateTime readingAt = input.readingAt() == null ? now() : input.readingAt();
            LocalDateTime now = now();
            jdbc.update("""
                    INSERT INTO meter_reading
                        (id, batch_id, meter_id, previous_reading, current_reading, raw_usage, multiplier,
                         correction, allocated_share, billable_usage, reading_at, status,
                         calculation_snapshot, version, created_at, updated_at)
                    VALUES (:id, :batchId, :meterId, :previous, :current, :raw, :multiplier,
                            :correction, :share, :billable, :readingAt, 'DRAFT', :snapshot, 0, :now, :now)
                    """, new MapSqlParameterSource("id", id).addValue("batchId", request.batchId())
                    .addValue("meterId", input.meterId()).addValue("previous", previous)
                    .addValue("current", input.currentReading()).addValue("raw", usage.raw())
                    .addValue("multiplier", multiplier).addValue("correction", correction).addValue("share", share)
                    .addValue("billable", usage.billable()).addValue("readingAt", readingAt)
                    .addValue("snapshot", json(Map.of("rawUsage", usage.raw(), "adjustedUsage", usage.adjusted(),
                            "billableUsage", usage.billable(), "assumptionRule", true))).addValue("now", now));
            created++;
        }
        audit.success(request.communityId(), "meter-reading:input", "meter-reading-batch", request.batchId(),
                Map.of("created", created, "replayed", replayed));
        return new InputResult(created, replayed);
    }

    @Transactional
    public InputResult importSimulated(String communityId, String batchId, List<String> meterIds) {
        security.requirePermission("meter:write");
        security.requireProject(communityId);
        List<ReadingInput> inputs = new ArrayList<>();
        for (String meterId : meterIds) {
            Map<String, Object> meter = requireMeter(communityId, meterId);
            IotAdapter.Reading reading = iotAdapter.read(String.valueOf(meter.get("meter_no")));
            inputs.add(new ReadingInput(meterId, null, reading.value(), null, BigDecimal.ZERO, reading.readAt()));
        }
        return input(new ReadingsInput(communityId, batchId, inputs));
    }

    @Transactional
    public Map<String, Object> approve(String batchId, String communityId) {
        security.requirePermission("meter:write");
        security.requireProject(communityId);
        Map<String, Object> batch = lockBatch(batchId, communityId);
        if ("APPROVED".equals(batch.get("status"))) return Map.of("batchId", batchId, "status", "APPROVED", "replayed", true);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM meter_reading WHERE batch_id=:batchId",
                Map.of("batchId", batchId), Long.class);
        if (count == null || count == 0) throw invalid("空批次不能审核");
        LocalDateTime now = now();
        jdbc.update("UPDATE meter_reading SET status='APPROVED', version=version+1, updated_at=:now WHERE batch_id=:batchId",
                Map.of("now", now, "batchId", batchId));
        jdbc.update("""
                UPDATE meter_reading_batch SET status='APPROVED', version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("now", now, "id", batchId));
        audit.success(communityId, "meter-batch:approve", "meter-reading-batch", batchId, Map.of("readingCount", count));
        return Map.of("batchId", batchId, "status", "APPROVED", "readingCount", count, "replayed", false);
    }

    public SharePreview sharePreview(ShareRequest request) {
        security.requirePermission("meter:read");
        security.requireProject(request.communityId());
        requireShareRule(request.communityId(), request.ruleId());
        Map<String, Object> batch = requireBatch(request.batchId(), request.communityId());
        requireDraftBatch(batch);
        return calculateSharePreview(request);
    }

    private SharePreview calculateSharePreview(ShareRequest request) {
        List<Map<String, Object>> assets = jdbc.queryForList("""
                SELECT id, display_name, building_area FROM asset
                WHERE community_id=:communityId AND asset_type='ROOM' AND enabled=TRUE AND building_area>0
                ORDER BY code
                """, Map.of("communityId", request.communityId()));
        BigDecimal totalArea = assets.stream().map(row -> decimal(row.get("building_area")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalArea.signum() == 0) throw invalid("没有可用于面积公摊的房屋");
        List<ShareLine> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int index = 0; index < assets.size(); index++) {
            Map<String, Object> asset = assets.get(index);
            BigDecimal area = decimal(asset.get("building_area"));
            BigDecimal usage = index == assets.size() - 1
                    ? request.totalUsage().subtract(allocated)
                    : request.totalUsage().multiply(area).divide(totalArea, 4, RoundingMode.HALF_UP);
            allocated = allocated.add(usage);
            lines.add(new ShareLine(String.valueOf(asset.get("id")), String.valueOf(asset.get("display_name")), area, usage));
        }
        return new SharePreview(request.totalUsage(), totalArea, allocated, true, lines);
    }

    @Transactional
    public ShareApplyResult applyShare(ShareRequest request) {
        security.requirePermission("meter:write");
        security.requireProject(request.communityId());
        requireShareRule(request.communityId(), request.ruleId());
        requireDraftBatch(lockBatch(request.batchId(), request.communityId()));
        SharePreview preview = calculateSharePreview(request);
        int inserted = 0;
        LocalDateTime now = now();
        for (ShareLine line : preview.items()) {
            int changed = jdbc.update("""
                    INSERT IGNORE INTO meter_share_result
                        (id, rule_id, batch_id, asset_id, allocated_usage, calculation_snapshot, created_at)
                    VALUES (:id, :ruleId, :batchId, :assetId, :usage, :snapshot, :now)
                    """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("ruleId", request.ruleId())
                    .addValue("batchId", request.batchId()).addValue("assetId", line.assetId())
                    .addValue("usage", line.allocatedUsage()).addValue("snapshot", json(Map.of(
                            "strategy", "AREA_RATIO", "area", line.area(), "totalArea", preview.totalArea(), "assumptionRule", true)))
                    .addValue("now", now));
            inserted += changed;
            jdbc.update("""
                    UPDATE meter_reading mr JOIN meter m ON m.id=mr.meter_id
                    SET mr.allocated_share=:usage,
                        mr.billable_usage=(mr.raw_usage * mr.multiplier) + mr.correction + :usage,
                        mr.version=mr.version+1, mr.updated_at=:now
                    WHERE mr.batch_id=:batchId AND m.asset_id=:assetId AND mr.status='DRAFT'
                    """, Map.of("usage", line.allocatedUsage(), "now", now, "batchId", request.batchId(), "assetId", line.assetId()));
        }
        audit.success(request.communityId(), "meter-share:apply", "meter-share-rule", request.ruleId(),
                Map.of("batchId", request.batchId(), "inserted", inserted, "assumptionRule", true));
        return new ShareApplyResult(inserted, preview.allocatedTotal(), true);
    }

    @Transactional
    public Map<String, Object> replace(String oldMeterId, ReplacementRequest request) {
        security.requirePermission("meter:write");
        security.requireProject(request.communityId());
        Map<String, Object> old = requireMeter(request.communityId(), oldMeterId);
        if (!"ACTIVE".equals(old.get("status"))) throw invalid("只有启用仪表可以换表");
        String newId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        var meterParams = new MapSqlParameterSource("id", newId).addValue("communityId", request.communityId())
                .addValue("assetId", old.get("asset_id")).addValue("parentId", old.get("parent_meter_id"))
                .addValue("meterNo", request.newMeterNo()).addValue("meterType", old.get("meter_type"))
                .addValue("meterClass", old.get("meter_class")).addValue("range", old.get("range_value"))
                .addValue("multiplier", old.get("multiplier")).addValue("lossRate", old.get("loss_rate"))
                .addValue("correction", old.get("correction")).addValue("now", now);
        jdbc.update("""
                INSERT INTO meter
                    (id, community_id, asset_id, parent_meter_id, meter_no, meter_type, meter_class, status,
                     range_value, multiplier, loss_rate, correction, installed_at, version, created_at, updated_at)
                VALUES (:id, :communityId, :assetId, :parentId, :meterNo, :meterType, :meterClass, 'ACTIVE',
                        :range, :multiplier, :lossRate, :correction, :now, 0, :now, :now)
                """, meterParams);
        jdbc.update("UPDATE meter SET status='REPLACED', version=version+1, updated_at=:now WHERE id=:id",
                Map.of("now", now, "id", oldMeterId));
        String replacementId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO meter_replacement
                    (id, old_meter_id, new_meter_id, old_final_reading, new_initial_reading,
                     replaced_at, reason, operated_by, created_at)
                VALUES (:id, :oldId, :newId, :oldReading, :newReading, :now, :reason, :userId, :now)
                """, Map.of("id", replacementId, "oldId", oldMeterId, "newId", newId,
                "oldReading", request.oldFinalReading(), "newReading", request.newInitialReading(),
                "now", now, "reason", request.reason(), "userId", security.requirePrincipal().userId()));
        audit.success(request.communityId(), "meter:replace", "meter", oldMeterId,
                Map.of("newMeterId", newId, "replacementId", replacementId));
        return Map.of("replacementId", replacementId, "oldMeterId", oldMeterId, "newMeterId", newId, "status", "COMPLETED");
    }

    @Transactional
    public ChargeResult generateCharges(String batchId, ChargeRequest request) {
        security.requirePermission("meter:write");
        security.requireProject(request.communityId());
        Map<String, Object> batch = lockBatch(batchId, request.communityId());
        if (!"APPROVED".equals(batch.get("status"))) throw invalid("抄表批次审核后才能生成费用");
        List<Map<String, Object>> standards = jdbc.queryForList("""
                SELECT fs.id standard_id, fd.id definition_id, fd.name item_name,
                       fsv.id version_id, fsv.unit_price
                FROM fee_standard fs JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                JOIN fee_standard_version fsv ON fsv.fee_standard_id=fs.id
                WHERE fs.id=:standardId AND fd.community_id=:communityId
                  AND fs.asset_type='ROOM' AND fs.status='ACTIVE' AND fsv.status='ACTIVE'
                ORDER BY fsv.version_no DESC LIMIT 1
                """, Map.of("standardId", request.feeStandardId(), "communityId", request.communityId()));
        if (standards.isEmpty()) throw invalid("计量费用标准不存在或已停用");
        Map<String, Object> standard = standards.get(0);
        List<Map<String, Object>> readings = jdbc.queryForList("""
                SELECT mr.id reading_id, mr.billable_usage, m.asset_id
                FROM meter_reading mr JOIN meter m ON m.id=mr.meter_id
                WHERE mr.batch_id=:batchId AND mr.status='APPROVED' AND m.asset_id IS NOT NULL
                ORDER BY mr.id
                """, Map.of("batchId", batchId));
        int generated = 0;
        int replayed = 0;
        BigDecimal total = BigDecimal.ZERO;
        String periodText = String.valueOf(batch.get("reading_period"));
        YearMonth period = YearMonth.parse(periodText);
        LocalDateTime now = now();
        for (Map<String, Object> reading : readings) {
            String readingId = String.valueOf(reading.get("reading_id"));
            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM bill_item WHERE source_type='METER_READING' AND source_id=:id",
                    Map.of("id", readingId), Long.class);
            if (exists != null && exists > 0) { replayed++; continue; }
            String assetId = String.valueOf(reading.get("asset_id"));
            BigDecimal quantity = decimal(reading.get("billable_usage"));
            BigDecimal unitPrice = decimal(standard.get("unit_price"));
            BigDecimal amount = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            String billId = findOrCreateBill(request.communityId(), assetId, period, now);
            jdbc.update("""
                    INSERT INTO bill_item
                        (id, bill_id, fee_definition_id, fee_standard_version_id, item_name_snapshot,
                         quantity, unit_price, coefficient, amount, calculation_snapshot,
                         source_type, source_id, created_at)
                    VALUES (:id, :billId, :definitionId, :versionId, :name,
                            :quantity, :unitPrice, 1, :amount, :snapshot,
                            'METER_READING', :readingId, :now)
                    """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("billId", billId)
                    .addValue("definitionId", standard.get("definition_id")).addValue("versionId", standard.get("version_id"))
                    .addValue("name", standard.get("item_name")).addValue("quantity", quantity)
                    .addValue("unitPrice", unitPrice).addValue("amount", amount).addValue("readingId", readingId)
                    .addValue("snapshot", json(Map.of("batchId", batchId, "readingId", readingId,
                            "formula", "billableUsage * unitPrice", "assumptionRule", true))).addValue("now", now));
            jdbc.update("""
                    UPDATE bill SET total_amount=total_amount+:amount,
                        outstanding_amount=outstanding_amount+:amount,
                        status=CASE WHEN paid_amount=0 THEN 'UNPAID' ELSE 'PARTIAL' END,
                        version=version+1, updated_at=:now WHERE id=:id
                    """, Map.of("amount", amount, "now", now, "id", billId));
            total = total.add(amount);
            generated++;
        }
        audit.success(request.communityId(), "meter-charge:generate", "meter-reading-batch", batchId,
                Map.of("generated", generated, "replayed", replayed, "total", total, "assumptionRule", true));
        return new ChargeResult(generated, replayed, total, true);
    }

    private String findOrCreateBill(String communityId, String assetId, YearMonth period, LocalDateTime now) {
        List<String> ids = jdbc.queryForList("""
                SELECT id FROM bill WHERE community_id=:communityId AND asset_id=:assetId AND billing_period=:period FOR UPDATE
                """, Map.of("communityId", communityId, "assetId", assetId, "period", period.toString()), String.class);
        if (!ids.isEmpty()) return ids.get(0);
        List<String> customers = jdbc.queryForList("""
                SELECT customer_id FROM customer_asset_relation WHERE asset_id=:assetId AND status='ACTIVE'
                ORDER BY primary_relation DESC, created_at LIMIT 1
                """, Map.of("assetId", assetId), String.class);
        String id = UUID.randomUUID().toString();
        var params = new MapSqlParameterSource("id", id).addValue("communityId", communityId).addValue("assetId", assetId)
                .addValue("customerId", customers.isEmpty() ? null : customers.get(0)).addValue("billNo", "MTR-" + period.toString().replace("-", "") + "-" + assetId)
                .addValue("period", period.toString()).addValue("dueDate", period.atEndOfMonth()).addValue("now", now);
        jdbc.update("""
                INSERT INTO bill
                    (id, community_id, asset_id, customer_id, bill_no, billing_period, status,
                     total_amount, paid_amount, outstanding_amount, due_date, version, created_at, updated_at)
                VALUES (:id, :communityId, :assetId, :customerId, :billNo, :period, 'UNPAID',
                        0, 0, 0, :dueDate, 0, :now, :now)
                """, params);
        return id;
    }

    private Map<String, Object> lockBatch(String id, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM meter_reading_batch WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("抄表批次不存在");
        return rows.get(0);
    }

    private Map<String, Object> requireBatch(String id, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM meter_reading_batch WHERE id=:id AND community_id=:communityId
                """, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("抄表批次不存在");
        return rows.get(0);
    }

    private void requireDraftBatch(Map<String, Object> batch) {
        if (!"DRAFT".equals(batch.get("status"))) throw invalid("只有草稿批次可以进行公摊试算或应用");
    }

    private Map<String, Object> requireMeter(String communityId, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM meter WHERE id=:id AND community_id=:communityId",
                Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("仪表不存在");
        return rows.get(0);
    }

    private void requireShareRule(String communityId, String id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM meter_share_rule WHERE id=:id AND community_id=:communityId AND status='ACTIVE'",
                Map.of("id", id, "communityId", communityId), Long.class);
        if (count == null || count == 0) throw notFound("公摊规则不存在或已停用");
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize meter snapshot", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_METER_OPERATION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("METER_RECORD_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    public record CreateBatch(@NotBlank String communityId, @NotBlank @Size(max = 100) String batchNo,
                              @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String readingPeriod,
                              @NotBlank @Pattern(regexp = "MANUAL|IOT_SIMULATOR") String sourceType) {}
    public record ReadingInput(@NotBlank String meterId, @DecimalMin("0") BigDecimal previousReading,
                               @NotNull @DecimalMin("0") BigDecimal currentReading,
                               BigDecimal correction, @DecimalMin("0") BigDecimal allocatedShare,
                               LocalDateTime readingAt) {}
    public record ReadingsInput(@NotBlank String communityId, @NotBlank String batchId,
                                @NotEmpty List<@Valid ReadingInput> readings) {}
    public record InputResult(int created, int replayed) {}
    public record ShareRequest(@NotBlank String communityId, @NotBlank String ruleId, @NotBlank String batchId,
                               @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal totalUsage) {}
    public record ShareLine(String assetId, String assetName, BigDecimal area, BigDecimal allocatedUsage) {}
    public record SharePreview(BigDecimal requestedTotal, BigDecimal totalArea, BigDecimal allocatedTotal,
                               boolean assumptionRule, List<ShareLine> items) {}
    public record ShareApplyResult(int inserted, BigDecimal allocatedTotal, boolean assumptionRule) {}
    public record ReplacementRequest(@NotBlank String communityId, @NotBlank @Size(max = 100) String newMeterNo,
                                     @NotNull @DecimalMin("0") BigDecimal oldFinalReading,
                                     @NotNull @DecimalMin("0") BigDecimal newInitialReading,
                                     @NotBlank @Size(max = 500) String reason) {}
    public record ChargeRequest(@NotBlank String communityId, @NotBlank String feeStandardId) {}
    public record ChargeResult(int generated, int replayed, BigDecimal totalAmount, boolean assumptionRule) {}
}
