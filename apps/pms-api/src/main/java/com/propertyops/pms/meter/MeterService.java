package com.propertyops.pms.meter;

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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
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

    public Map<String, Object> workbench(String communityId) {
        requireRead(communityId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM meter WHERE community_id=:communityId) meterCount,
                       (SELECT COUNT(*) FROM meter WHERE community_id=:communityId AND status='ACTIVE') activeMeterCount,
                       (SELECT COUNT(*) FROM meter_reading_batch WHERE community_id=:communityId) batchCount,
                       (SELECT COUNT(*) FROM meter_reading mr JOIN meter_reading_batch mb ON mb.id=mr.batch_id
                         WHERE mb.community_id=:communityId AND mr.validation_status='REVIEW_REQUIRED') pendingReviewCount,
                       (SELECT COUNT(*) FROM iot_reading_inbox WHERE community_id=:communityId) iotEvidenceCount,
                       (SELECT COUNT(*) FROM meter_charge_reconciliation
                         WHERE community_id=:communityId AND status='MISMATCH') reconciliationMismatchCount
                """, Map.of("communityId", communityId)));
        result.put("meters", meterRows(communityId));
        result.put("batches", batchRows(communityId));
        result.put("shareRules", jdbc.queryForList("""
                SELECT r.id, r.code, r.name, r.strategy_code strategyCode, r.status,
                       v.id activeVersionId, v.version_no activeVersionNo, v.effective_from effectiveFrom,
                       v.effective_to effectiveTo, v.assumption_rule assumptionRule
                FROM meter_share_rule r
                LEFT JOIN meter_share_rule_version v ON v.rule_id=r.id AND v.status='ACTIVE'
                WHERE r.community_id=:communityId ORDER BY r.code, v.version_no DESC
                """, Map.of("communityId", communityId)));
        result.put("feeStandards", jdbc.queryForList("""
                SELECT fs.id, fs.code, fs.name, fs.status, fsv.version_no versionNo,
                       fsv.unit_price unitPrice, fsv.effective_from effectiveFrom, fsv.effective_to effectiveTo,
                       fd.rounding_mode roundingMode
                FROM fee_standard fs JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                JOIN fee_standard_version fsv ON fsv.fee_standard_id=fs.id AND fsv.status='ACTIVE'
                WHERE fs.community_id=:communityId AND fs.asset_type='METER' AND fs.calculation_basis='METER_USAGE'
                ORDER BY fs.code, fsv.version_no DESC
                """, Map.of("communityId", communityId)));
        return result;
    }

    public List<Map<String, Object>> meters(String communityId) {
        requireRead(communityId);
        return meterRows(communityId);
    }

    private List<Map<String, Object>> meterRows(String communityId) {
        return jdbc.queryForList("""
                SELECT m.id, m.meter_no meterNo, m.meter_type meterType, m.meter_class meterClass,
                       m.status, m.range_value rangeValue, m.multiplier, m.loss_rate lossRate,
                       m.correction, m.installed_at installedAt, m.version,
                       m.asset_id assetId, a.code assetCode, a.display_name assetName,
                       m.parent_meter_id parentMeterId, p.meter_no parentMeterNo,
                       latest.reading_period lastReadingPeriod, latest.current_reading lastReading,
                       latest.calculation_checksum lastReadingChecksum
                FROM meter m LEFT JOIN asset a ON a.id=m.asset_id LEFT JOIN meter p ON p.id=m.parent_meter_id
                LEFT JOIN meter_reading latest ON latest.id=(SELECT mr.id FROM meter_reading mr
                    WHERE mr.meter_id=m.id AND mr.status='APPROVED'
                    ORDER BY mr.reading_period DESC, mr.reading_at DESC LIMIT 1)
                WHERE m.community_id=:communityId ORDER BY m.meter_class, m.meter_no
                """, Map.of("communityId", communityId));
    }

    public List<Map<String, Object>> batches(String communityId) {
        requireRead(communityId);
        return batchRows(communityId);
    }

    private List<Map<String, Object>> batchRows(String communityId) {
        return jdbc.queryForList("""
                SELECT id, batch_no batchNo, reading_period readingPeriod, status, source_type sourceType,
                       total_count totalCount, normal_count normalCount, anomaly_count anomalyCount,
                       reviewed_count reviewedCount, approved_by approvedBy, approved_at approvedAt,
                       data_checksum dataChecksum, version, created_at createdAt, updated_at updatedAt
                FROM meter_reading_batch WHERE community_id=:communityId
                ORDER BY reading_period DESC, created_at DESC
                """, Map.of("communityId", communityId));
    }

    public Map<String, Object> batchDetail(String batchId, String communityId) {
        requireRead(communityId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch", requireBatch(batchId, communityId));
        result.put("readings", jdbc.queryForList("""
                SELECT mr.id, mr.reading_period readingPeriod, mr.input_source inputSource,
                       mr.source_reference sourceReference, mr.previous_reading previousReading,
                       mr.current_reading currentReading, mr.raw_usage rawUsage, mr.multiplier,
                       mr.loss_rate lossRate, mr.adjusted_usage adjustedUsage, mr.correction,
                       mr.allocated_share allocatedShare, mr.billable_usage billableUsage,
                       mr.validation_status validationStatus, mr.anomaly_code anomalyCode,
                       mr.review_reason reviewReason, mr.reviewed_by reviewedBy, mr.reviewed_at reviewedAt,
                       mr.status, mr.calculation_snapshot calculationSnapshot,
                       mr.calculation_checksum calculationChecksum, mr.version,
                       m.id meterId, m.meter_no meterNo, a.display_name assetName
                FROM meter_reading mr JOIN meter m ON m.id=mr.meter_id LEFT JOIN asset a ON a.id=m.asset_id
                WHERE mr.batch_id=:batchId ORDER BY m.meter_no
                """, Map.of("batchId", batchId)));
        result.put("iotEvidence", jdbc.queryForList("""
                SELECT id, adapter_code adapterCode, source_reference sourceReference,
                       reading_value readingValue, reading_at readingAt, simulated,
                       payload_checksum payloadChecksum, status, meter_reading_id meterReadingId,
                       created_at createdAt
                FROM iot_reading_inbox WHERE batch_id=:batchId ORDER BY created_at
                """, Map.of("batchId", batchId)));
        result.put("reconciliation", reconciliationRows(batchId));
        return result;
    }

    @Transactional
    public Map<String, Object> createBatch(CreateBatch request, String idempotencyKey) {
        requireWrite(request.communityId());
        YearMonth.parse(request.readingPeriod());
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = sha256(String.join("|", request.communityId(), request.batchNo(),
                request.readingPeriod(), request.sourceType()));
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT * FROM meter_reading_batch WHERE community_id=:communityId AND request_key=:requestKey
                """, Map.of("communityId", request.communityId(), "requestKey", key));
        if (!replay.isEmpty()) {
            requireSameHash(replay.get(0).get("request_hash"), requestHash);
            return withReplay(replay.get(0));
        }
        Long duplicate = jdbc.queryForObject("""
                SELECT COUNT(*) FROM meter_reading_batch WHERE community_id=:communityId AND batch_no=:batchNo
                """, Map.of("communityId", request.communityId(), "batchNo", request.batchNo()), Long.class);
        if (duplicate != null && duplicate > 0) {
            throw conflict("METER_BATCH_NUMBER_CONFLICT", "批次号已存在，请使用原 Idempotency-Key 重放");
        }
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String checksum = sha256("meter-batch|" + id + "|" + requestHash);
        jdbc.update("""
                INSERT INTO meter_reading_batch
                    (id, community_id, batch_no, reading_period, status, source_type,
                     request_key, request_hash, total_count, normal_count, anomaly_count, reviewed_count,
                     data_checksum, version, created_at, updated_at)
                VALUES (:id, :communityId, :batchNo, :period, 'DRAFT', :sourceType,
                        :requestKey, :requestHash, 0, 0, 0, 0, :checksum, 0, :now, :now)
                """, params("id", id, "communityId", request.communityId(), "batchNo", request.batchNo(),
                "period", request.readingPeriod(), "sourceType", request.sourceType(), "requestKey", key,
                "requestHash", requestHash, "checksum", checksum, "now", now));
        event(request.communityId(), "BATCH", id, "BATCH_CREATED", null, null,
                ordered("readingPeriod", request.readingPeriod(), "sourceType", request.sourceType(),
                        "requestHash", requestHash));
        audit.success(request.communityId(), "meter-batch:create", "meter-reading-batch", id,
                Map.of("period", request.readingPeriod(), "sourceType", request.sourceType()));
        return jdbc.queryForMap("SELECT *, FALSE replayed FROM meter_reading_batch WHERE id=:id", Map.of("id", id));
    }

    @Transactional
    public InputResult input(ReadingsInput request) {
        requireWrite(request.communityId());
        Map<String, Object> batch = lockBatch(request.batchId(), request.communityId());
        requireDraft(batch);
        int created = 0, replayed = 0, anomalies = 0;
        for (ReadingInput input : request.readings()) {
            InsertOutcome outcome = insertReading(batch, request.communityId(), input, "MANUAL",
                    "MANUAL:" + request.batchId() + ":" + input.meterId());
            if (outcome.created()) created++; else replayed++;
            if (outcome.anomaly()) anomalies++;
        }
        refreshBatch(request.batchId());
        event(request.communityId(), "BATCH", request.batchId(), "READINGS_INPUT", null, null,
                ordered("created", created, "replayed", replayed, "anomalies", anomalies));
        audit.success(request.communityId(), "meter-reading:input", "meter-reading-batch", request.batchId(),
                Map.of("created", created, "replayed", replayed, "anomalies", anomalies));
        return new InputResult(created, replayed, anomalies);
    }

    @Transactional
    public InputResult importSimulated(String communityId, String batchId, List<String> meterIds) {
        requireWrite(communityId);
        if (meterIds == null || meterIds.isEmpty()) throw invalid("至少选择一个仪表进行 IoT 模拟导入");
        Map<String, Object> batch = lockBatch(batchId, communityId);
        requireDraft(batch);
        int created = 0, replayed = 0, anomalies = 0;
        for (String meterId : meterIds) {
            Map<String, Object> meter = requireMeter(communityId, meterId);
            String reference = "SIM:" + batchId + ":" + meterId;
            List<Map<String, Object>> evidence = jdbc.queryForList("""
                    SELECT * FROM iot_reading_inbox WHERE adapter_code=:adapter AND source_reference=:reference
                    """, Map.of("adapter", iotAdapter.code(), "reference", reference));
            if (!evidence.isEmpty()) {
                jdbc.update("UPDATE iot_reading_inbox SET status='REPLAYED' WHERE id=:id",
                        Map.of("id", evidence.get(0).get("id")));
                replayed++;
                continue;
            }
            IotAdapter.Reading reading = iotAdapter.read(text(meter.get("meter_no")));
            String payload = json(ordered("adapterCode", iotAdapter.code(), "meterNo", reading.meterNo(),
                    "value", reading.value(), "readAt", reading.readAt(), "simulated", reading.simulated()));
            String inboxId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO iot_reading_inbox
                        (id, community_id, batch_id, meter_id, adapter_code, source_reference,
                         reading_value, reading_at, simulated, payload_json, payload_checksum,
                         status, meter_reading_id, created_at)
                    VALUES (:id, :communityId, :batchId, :meterId, :adapter, :reference,
                            :value, :readAt, :simulated, :payload, :checksum, 'RECEIVED', NULL, :now)
                    """, params("id", inboxId, "communityId", communityId, "batchId", batchId,
                    "meterId", meterId, "adapter", iotAdapter.code(), "reference", reference,
                    "value", reading.value(), "readAt", reading.readAt(), "simulated", reading.simulated(),
                    "payload", payload, "checksum", sha256(payload), "now", now()));
            InsertOutcome outcome = insertReading(batch, communityId,
                    new ReadingInput(meterId, null, reading.value(), null, BigDecimal.ZERO, reading.readAt()),
                    "IOT_SIMULATOR", reference);
            jdbc.update("UPDATE iot_reading_inbox SET status='APPLIED', meter_reading_id=:readingId WHERE id=:id",
                    Map.of("readingId", outcome.readingId(), "id", inboxId));
            if (outcome.created()) created++; else replayed++;
            if (outcome.anomaly()) anomalies++;
        }
        refreshBatch(batchId);
        event(communityId, "BATCH", batchId, "IOT_SIMULATED_IMPORT", null, null,
                ordered("adapterCode", iotAdapter.code(), "created", created, "replayed", replayed,
                        "anomalies", anomalies, "simulated", true));
        return new InputResult(created, replayed, anomalies);
    }

    private InsertOutcome insertReading(Map<String, Object> batch, String communityId, ReadingInput input,
                                        String source, String sourceReference) {
        Map<String, Object> meter = requireMeter(communityId, input.meterId());
        if (!"ACTIVE".equals(text(meter.get("status")))) throw invalid("只有启用仪表可以录入读数");
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, current_reading, input_source, validation_status FROM meter_reading
                WHERE meter_id=:meterId AND batch_id=:batchId
                """, Map.of("meterId", input.meterId(), "batchId", batch.get("id")));
        if (!existing.isEmpty()) {
            Map<String, Object> prior = existing.get(0);
            if (decimal(prior.get("current_reading")).compareTo(input.currentReading()) != 0
                    || !source.equals(text(prior.get("input_source")))) {
                throw conflict("READING_ALREADY_EXISTS", "该批次已录入此仪表，且读数或来源不同");
            }
            return new InsertOutcome(text(prior.get("id")), false,
                    !"NORMAL".equals(text(prior.get("validation_status"))));
        }
        String readingPeriod = text(batch.get("reading_period"));
        LocalDateTime readingAt = input.readingAt() == null ? now() : input.readingAt();
        if (!YearMonth.from(readingAt).equals(YearMonth.parse(readingPeriod))) {
            throw new BusinessException("METER_READING_CROSS_PERIOD", "读数时间必须属于批次周期 " + readingPeriod,
                    HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT id, reading_period, current_reading, calculation_checksum FROM meter_reading
                WHERE meter_id=:meterId AND status='APPROVED'
                ORDER BY reading_period DESC, reading_at DESC LIMIT 1
                """, Map.of("meterId", input.meterId()));
        Map<String, Object> previousRow = history.isEmpty() ? null : history.get(0);
        if (previousRow != null && YearMonth.parse(text(previousRow.get("reading_period")))
                .compareTo(YearMonth.parse(readingPeriod)) >= 0) {
            throw new BusinessException("METER_READING_PERIOD_SEQUENCE", "批次周期必须晚于该仪表最后一个已审核周期",
                    HttpStatus.CONFLICT);
        }
        BigDecimal expectedPrevious = previousRow == null ? BigDecimal.ZERO : decimal(previousRow.get("current_reading"));
        BigDecimal previous = input.previousReading() == null ? expectedPrevious : input.previousReading();
        BigDecimal multiplier = decimal(meter.get("multiplier"));
        BigDecimal lossRate = decimal(meter.get("loss_rate"));
        BigDecimal defaultCorrection = decimal(meter.get("correction"));
        BigDecimal correction = input.correction() == null ? defaultCorrection : input.correction();
        BigDecimal share = input.allocatedShare() == null ? BigDecimal.ZERO : input.allocatedShare();
        MeterUsageCalculator.Usage usage = MeterUsageCalculator.calculate(previous, input.currentReading(),
                multiplier, lossRate, correction, share);
        String anomaly = previous.compareTo(expectedPrevious) == 0 ? null : "PREVIOUS_MISMATCH";
        BigDecimal range = meter.get("range_value") == null ? null : decimal(meter.get("range_value"));
        if (anomaly == null && range != null && usage.raw().compareTo(range) > 0) anomaly = "RANGE_EXCEEDED";
        if (anomaly == null && correction.compareTo(defaultCorrection) != 0) anomaly = "MANUAL_CORRECTION";
        String validation = anomaly == null ? "NORMAL" : "REVIEW_REQUIRED";
        String id = UUID.randomUUID().toString();
        String snapshot = json(ordered(
                "formula", "(current - previous) * multiplier * (1 + lossRate) + correction + allocatedShare",
                "readingPeriod", readingPeriod, "meterId", input.meterId(), "meterVersion", meter.get("version"),
                "previousReadingId", previousRow == null ? null : previousRow.get("id"),
                "previousReadingChecksum", previousRow == null ? null : previousRow.get("calculation_checksum"),
                "expectedPrevious", expectedPrevious, "previous", previous, "current", input.currentReading(),
                "rawUsage", usage.raw(), "multiplier", multiplier, "lossRate", lossRate,
                "adjustedUsage", usage.adjusted(), "correction", correction, "allocatedShare", share,
                "billableUsage", usage.billable(), "inputSource", source, "sourceReference", sourceReference,
                "validationStatus", validation, "anomalyCode", anomaly, "assumptionRule", true));
        jdbc.update("""
                INSERT INTO meter_reading
                    (id, batch_id, meter_id, reading_period, input_source, source_reference,
                     previous_reading, current_reading, raw_usage, multiplier, loss_rate, adjusted_usage,
                     correction, allocated_share, billable_usage, reading_at, status,
                     validation_status, anomaly_code, calculation_snapshot, calculation_checksum,
                     version, created_at, updated_at)
                VALUES (:id, :batchId, :meterId, :period, :source, :reference,
                        :previous, :current, :raw, :multiplier, :lossRate, :adjusted,
                        :correction, :share, :billable, :readingAt, 'DRAFT',
                        :validation, :anomaly, :snapshot, :checksum, 0, :now, :now)
                """, params("id", id, "batchId", batch.get("id"), "meterId", input.meterId(),
                "period", readingPeriod, "source", source, "reference", sourceReference,
                "previous", previous, "current", input.currentReading(), "raw", usage.raw(),
                "multiplier", multiplier, "lossRate", lossRate, "adjusted", usage.adjusted(),
                "correction", correction, "share", share, "billable", usage.billable(),
                "readingAt", readingAt, "validation", validation, "anomaly", anomaly,
                "snapshot", snapshot, "checksum", sha256(snapshot), "now", now()));
        event(communityId, "READING", id, anomaly == null ? "READING_RECORDED" : "READING_ANOMALY_DETECTED",
                "BATCH", text(batch.get("id")), ordered("validationStatus", validation,
                        "anomalyCode", anomaly, "calculationChecksum", sha256(snapshot)));
        return new InsertOutcome(id, true, anomaly != null);
    }

    @Transactional
    public Map<String, Object> reviewReading(String readingId, ReviewRequest request) {
        requireWrite(request.communityId());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT mr.*, mb.community_id FROM meter_reading mr JOIN meter_reading_batch mb ON mb.id=mr.batch_id
                WHERE mr.id=:id AND mb.community_id=:communityId FOR UPDATE
                """, Map.of("id", readingId, "communityId", request.communityId()));
        if (rows.isEmpty()) throw notFound("抄表读数不存在");
        Map<String, Object> row = rows.get(0);
        if (number(row.get("version")) != request.expectedVersion()) throw versionConflict();
        if ("REVIEWED".equals(text(row.get("validation_status")))) return withReplay(row);
        if (!"REVIEW_REQUIRED".equals(text(row.get("validation_status")))) throw invalid("正常读数无需异常复核");
        String userId = security.requirePrincipal().userId();
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE meter_reading SET validation_status='REVIEWED', review_reason=:reason,
                    reviewed_by=:userId, reviewed_at=:now, version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("reason", request.reason(), "userId", userId, "now", now, "id", readingId));
        refreshBatch(text(row.get("batch_id")));
        event(request.communityId(), "READING", readingId, "READING_ANOMALY_REVIEWED", "BATCH",
                text(row.get("batch_id")), ordered("reason", request.reason(), "anomalyCode", row.get("anomaly_code")));
        return jdbc.queryForMap("SELECT *, FALSE replayed FROM meter_reading WHERE id=:id", Map.of("id", readingId));
    }

    @Transactional
    public Map<String, Object> approve(String batchId, String communityId) {
        requireWrite(communityId);
        Map<String, Object> batch = lockBatch(batchId, communityId);
        if ("APPROVED".equals(text(batch.get("status")))) {
            return ordered("batchId", batchId, "status", "APPROVED", "replayed", true,
                    "dataChecksum", batch.get("data_checksum"));
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM meter_reading WHERE batch_id=:batchId",
                Map.of("batchId", batchId), Long.class);
        if (count == null || count == 0) throw invalid("空批次不能审核");
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM meter_reading WHERE batch_id=:batchId AND validation_status='REVIEW_REQUIRED'
                """, Map.of("batchId", batchId), Long.class);
        if (pending != null && pending > 0) {
            throw conflict("METER_ANOMALY_REVIEW_REQUIRED", "存在未复核异常读数，不能审核批次");
        }
        String aggregate = jdbc.queryForObject("""
                SELECT GROUP_CONCAT(CONCAT(id, ':', calculation_checksum, ':', validation_status)
                                    ORDER BY meter_id SEPARATOR '|')
                FROM meter_reading WHERE batch_id=:batchId
                """, Map.of("batchId", batchId), String.class);
        String checksum = sha256("approved-meter-batch|" + batchId + "|" + aggregate);
        LocalDateTime now = now();
        String userId = security.requirePrincipal().userId();
        jdbc.update("UPDATE meter_reading SET status='APPROVED', version=version+1, updated_at=:now WHERE batch_id=:id",
                Map.of("now", now, "id", batchId));
        jdbc.update("""
                UPDATE meter_reading_batch SET status='APPROVED', approved_by=:userId, approved_at=:now,
                    data_checksum=:checksum, version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("userId", userId, "now", now, "checksum", checksum, "id", batchId));
        event(communityId, "BATCH", batchId, "BATCH_APPROVED", null, null,
                ordered("readingCount", count, "dataChecksum", checksum));
        audit.success(communityId, "meter-batch:approve", "meter-reading-batch", batchId,
                Map.of("readingCount", count, "dataChecksum", checksum));
        return ordered("batchId", batchId, "status", "APPROVED", "readingCount", count,
                "dataChecksum", checksum, "replayed", false);
    }

    public SharePreview sharePreview(ShareRequest request) {
        requireRead(request.communityId());
        Map<String, Object> batch = requireBatch(request.batchId(), request.communityId());
        requireDraft(batch);
        Map<String, Object> version = shareRuleVersion(request.communityId(), request.ruleId(),
                YearMonth.parse(text(batch.get("reading_period"))));
        return calculateShare(request, version);
    }

    private SharePreview calculateShare(ShareRequest request, Map<String, Object> version) {
        List<Map<String, Object>> assets = jdbc.queryForList("""
                SELECT DISTINCT a.id, a.display_name, a.building_area
                FROM meter_reading mr JOIN meter m ON m.id=mr.meter_id JOIN asset a ON a.id=m.asset_id
                WHERE mr.batch_id=:batchId AND a.community_id=:communityId
                  AND a.asset_type='ROOM' AND a.enabled=TRUE AND a.building_area>0 ORDER BY a.id
                """, Map.of("batchId", request.batchId(), "communityId", request.communityId()));
        if (assets.isEmpty()) throw invalid("当前批次没有可用于面积公摊的房屋仪表");
        BigDecimal totalArea = assets.stream().map(row -> decimal(row.get("building_area")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ShareLine> lines = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int index = 0; index < assets.size(); index++) {
            Map<String, Object> asset = assets.get(index);
            BigDecimal area = decimal(asset.get("building_area"));
            BigDecimal usage = index == assets.size() - 1
                    ? request.totalUsage().subtract(allocated)
                    : request.totalUsage().multiply(area).divide(totalArea, 4, RoundingMode.HALF_UP);
            allocated = allocated.add(usage);
            lines.add(new ShareLine(text(asset.get("id")), text(asset.get("display_name")), area, usage));
        }
        return new SharePreview(request.totalUsage(), totalArea, allocated,
                Boolean.TRUE.equals(version.get("assumption_rule")), text(version.get("id")),
                number(version.get("version_no")), lines);
    }

    @Transactional
    public ShareApplyResult applyShare(ShareRequest request) {
        requireWrite(request.communityId());
        Map<String, Object> batch = lockBatch(request.batchId(), request.communityId());
        requireDraft(batch);
        Map<String, Object> version = shareRuleVersion(request.communityId(), request.ruleId(),
                YearMonth.parse(text(batch.get("reading_period"))));
        SharePreview preview = calculateShare(request, version);
        int inserted = 0, replayed = 0;
        LocalDateTime now = now();
        for (ShareLine line : preview.items()) {
            String snapshot = json(ordered("strategy", "AREA_RATIO", "ruleId", request.ruleId(),
                    "ruleVersionId", preview.ruleVersionId(), "ruleVersionNo", preview.ruleVersionNo(),
                    "allocationBasis", line.area(), "basisTotal", preview.totalArea(),
                    "requestedTotal", preview.requestedTotal(), "allocatedUsage", line.allocatedUsage(),
                    "assumptionRule", preview.assumptionRule()));
            int changed = jdbc.update("""
                    INSERT IGNORE INTO meter_share_result
                        (id, rule_id, rule_version_id, batch_id, asset_id, allocation_basis, basis_total,
                         allocated_usage, calculation_snapshot, calculation_checksum, created_at)
                    VALUES (:id, :ruleId, :versionId, :batchId, :assetId, :basis, :basisTotal,
                            :usage, :snapshot, :checksum, :now)
                    """, params("id", UUID.randomUUID().toString(), "ruleId", request.ruleId(),
                    "versionId", preview.ruleVersionId(), "batchId", request.batchId(), "assetId", line.assetId(),
                    "basis", line.area(), "basisTotal", preview.totalArea(), "usage", line.allocatedUsage(),
                    "snapshot", snapshot, "checksum", sha256(snapshot), "now", now));
            if (changed == 0) {
                Map<String, Object> prior = jdbc.queryForMap("""
                        SELECT allocated_usage, calculation_checksum FROM meter_share_result
                        WHERE rule_id=:ruleId AND batch_id=:batchId AND asset_id=:assetId
                        """, Map.of("ruleId", request.ruleId(), "batchId", request.batchId(), "assetId", line.assetId()));
                if (decimal(prior.get("allocated_usage")).compareTo(line.allocatedUsage()) != 0
                        || !sha256(snapshot).equals(text(prior.get("calculation_checksum")))) {
                    throw conflict("METER_SHARE_CONFLICT", "该批次已使用不同参数应用公摊");
                }
                replayed++;
                continue;
            }
            inserted++;
            List<Map<String, Object>> readings = jdbc.queryForList("""
                    SELECT mr.* FROM meter_reading mr JOIN meter m ON m.id=mr.meter_id
                    WHERE mr.batch_id=:batchId AND m.asset_id=:assetId AND mr.status='DRAFT' FOR UPDATE
                    """, Map.of("batchId", request.batchId(), "assetId", line.assetId()));
            for (Map<String, Object> reading : readings) {
                MeterUsageCalculator.Usage usage = MeterUsageCalculator.calculate(
                        decimal(reading.get("previous_reading")), decimal(reading.get("current_reading")),
                        decimal(reading.get("multiplier")), decimal(reading.get("loss_rate")),
                        decimal(reading.get("correction")), line.allocatedUsage());
                String readingSnapshot = json(ordered(
                        "formula", "(current - previous) * multiplier * (1 + lossRate) + correction + allocatedShare",
                        "readingPeriod", reading.get("reading_period"), "meterId", reading.get("meter_id"),
                        "previous", reading.get("previous_reading"), "current", reading.get("current_reading"),
                        "rawUsage", usage.raw(), "multiplier", reading.get("multiplier"),
                        "lossRate", reading.get("loss_rate"), "adjustedUsage", usage.adjusted(),
                        "correction", reading.get("correction"), "allocatedShare", line.allocatedUsage(),
                        "billableUsage", usage.billable(), "shareRuleVersionId", preview.ruleVersionId(),
                        "shareRuleVersionNo", preview.ruleVersionNo(), "assumptionRule", preview.assumptionRule()));
                jdbc.update("""
                        UPDATE meter_reading SET allocated_share=:share, adjusted_usage=:adjusted,
                            billable_usage=:billable, calculation_snapshot=:snapshot,
                            calculation_checksum=:checksum, version=version+1, updated_at=:now WHERE id=:id
                        """, params("share", line.allocatedUsage(), "adjusted", usage.adjusted(),
                        "billable", usage.billable(), "snapshot", readingSnapshot,
                        "checksum", sha256(readingSnapshot), "now", now, "id", reading.get("id")));
            }
        }
        refreshBatch(request.batchId());
        event(request.communityId(), "SHARE", request.ruleId(), "SHARE_APPLIED", "BATCH", request.batchId(),
                ordered("ruleVersionId", preview.ruleVersionId(), "inserted", inserted, "replayed", replayed,
                        "allocatedTotal", preview.allocatedTotal(), "assumptionRule", preview.assumptionRule()));
        audit.success(request.communityId(), "meter-share:apply", "meter-share-rule", request.ruleId(),
                Map.of("batchId", request.batchId(), "inserted", inserted, "replayed", replayed));
        return new ShareApplyResult(inserted, replayed, preview.allocatedTotal(), preview.assumptionRule(),
                preview.ruleVersionId(), preview.ruleVersionNo());
    }

    private Map<String, Object> shareRuleVersion(String communityId, String ruleId, YearMonth period) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT v.* FROM meter_share_rule r JOIN meter_share_rule_version v ON v.rule_id=r.id
                WHERE r.id=:ruleId AND r.community_id=:communityId AND r.status='ACTIVE'
                  AND v.status='ACTIVE' AND v.effective_from<=:periodDate
                  AND (v.effective_to IS NULL OR v.effective_to>=:periodDate)
                ORDER BY v.version_no DESC LIMIT 1
                """, Map.of("ruleId", ruleId, "communityId", communityId, "periodDate", period.atDay(1)));
        if (rows.isEmpty()) throw notFound("公摊规则不存在或当前周期无有效版本");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> replace(String oldMeterId, ReplacementRequest request, String idempotencyKey) {
        requireWrite(request.communityId());
        String key = requireIdempotencyKey(idempotencyKey);
        String requestHash = sha256(String.join("|", request.communityId(), oldMeterId, request.newMeterNo(),
                request.oldFinalReading().toPlainString(), request.newInitialReading().toPlainString(), request.reason()));
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT * FROM meter_replacement WHERE community_id=:communityId AND request_key=:requestKey
                """, Map.of("communityId", request.communityId(), "requestKey", key));
        if (!replay.isEmpty()) {
            requireSameHash(replay.get(0).get("request_hash"), requestHash);
            return replacementResult(replay.get(0), true);
        }
        Map<String, Object> old = lockMeter(request.communityId(), oldMeterId);
        if (!"ACTIVE".equals(text(old.get("status")))) throw invalid("只有启用仪表可以换表");
        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT id, current_reading, calculation_checksum FROM meter_reading
                WHERE meter_id=:meterId AND status='APPROVED'
                ORDER BY reading_period DESC, reading_at DESC LIMIT 1
                """, Map.of("meterId", oldMeterId));
        BigDecimal expectedFinal = history.isEmpty() ? BigDecimal.ZERO : decimal(history.get(0).get("current_reading"));
        if (request.oldFinalReading().compareTo(expectedFinal) != 0) {
            throw conflict("METER_REPLACEMENT_CONTINUITY", "旧表止码必须等于最后一个已审核读数 " + expectedFinal);
        }
        BigDecimal range = old.get("range_value") == null ? null : decimal(old.get("range_value"));
        if (range != null && request.newInitialReading().compareTo(range) > 0) throw invalid("新表起码不能超过仪表量程");
        Long duplicate = jdbc.queryForObject("""
                SELECT COUNT(*) FROM meter WHERE community_id=:communityId AND meter_no=:meterNo
                """, Map.of("communityId", request.communityId(), "meterNo", request.newMeterNo()), Long.class);
        if (duplicate != null && duplicate > 0) throw conflict("METER_NUMBER_CONFLICT", "新表编号已存在");
        String newId = UUID.randomUUID().toString();
        String replacementId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String evidenceNo = "MR-" + now.toLocalDate().toString().replace("-", "") + "-"
                + replacementId.substring(0, 8).toUpperCase();
        int oldVersion = number(old.get("version"));
        jdbc.update("""
                INSERT INTO meter
                    (id, community_id, asset_id, parent_meter_id, meter_no, meter_type, meter_class, status,
                     range_value, multiplier, loss_rate, correction, installed_at, version, created_at, updated_at)
                VALUES (:id, :communityId, :assetId, :parentId, :meterNo, :meterType, :meterClass, 'ACTIVE',
                        :range, :multiplier, :lossRate, :correction, :now, 0, :now, :now)
                """, params("id", newId, "communityId", request.communityId(), "assetId", old.get("asset_id"),
                "parentId", old.get("parent_meter_id"), "meterNo", request.newMeterNo(),
                "meterType", old.get("meter_type"), "meterClass", old.get("meter_class"),
                "range", old.get("range_value"), "multiplier", old.get("multiplier"),
                "lossRate", old.get("loss_rate"), "correction", old.get("correction"), "now", now));
        int updated = jdbc.update("""
                UPDATE meter SET status='REPLACED', version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("now", now, "id", oldMeterId, "version", oldVersion));
        if (updated != 1) throw versionConflict();
        String snapshot = json(ordered("evidenceNo", evidenceNo, "oldMeterId", oldMeterId,
                "oldMeterNo", old.get("meter_no"), "oldMeterVersion", oldVersion,
                "oldFinalReading", request.oldFinalReading(),
                "lastApprovedReadingId", history.isEmpty() ? null : history.get(0).get("id"),
                "lastApprovedReadingChecksum", history.isEmpty() ? null : history.get(0).get("calculation_checksum"),
                "newMeterId", newId, "newMeterNo", request.newMeterNo(), "newMeterVersion", 0,
                "newInitialReading", request.newInitialReading(), "reason", request.reason()));
        jdbc.update("""
                INSERT INTO meter_replacement
                    (id, community_id, request_key, request_hash, evidence_no, old_meter_id, new_meter_id,
                     old_final_reading, new_initial_reading, old_meter_version, new_meter_version,
                     replaced_at, reason, snapshot_json, snapshot_checksum, operated_by, created_at)
                VALUES (:id, :communityId, :requestKey, :requestHash, :evidenceNo, :oldId, :newId,
                        :oldReading, :newReading, :oldVersion, 0, :now, :reason, :snapshot, :checksum,
                        :userId, :now)
                """, params("id", replacementId, "communityId", request.communityId(), "requestKey", key,
                "requestHash", requestHash, "evidenceNo", evidenceNo, "oldId", oldMeterId, "newId", newId,
                "oldReading", request.oldFinalReading(), "newReading", request.newInitialReading(),
                "oldVersion", oldVersion, "now", now, "reason", request.reason(), "snapshot", snapshot,
                "checksum", sha256(snapshot), "userId", security.requirePrincipal().userId()));
        event(request.communityId(), "REPLACEMENT", replacementId, "METER_REPLACED", "METER", oldMeterId,
                ordered("evidenceNo", evidenceNo, "newMeterId", newId, "snapshotChecksum", sha256(snapshot)));
        audit.success(request.communityId(), "meter:replace", "meter", oldMeterId,
                Map.of("newMeterId", newId, "replacementId", replacementId, "evidenceNo", evidenceNo));
        return replacementResult(jdbc.queryForMap("SELECT * FROM meter_replacement WHERE id=:id",
                Map.of("id", replacementId)), false);
    }

    private Map<String, Object> lockMeter(String communityId, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM meter WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("仪表不存在");
        return rows.get(0);
    }

    private Map<String, Object> replacementResult(Map<String, Object> row, boolean replayed) {
        return ordered("replacementId", row.get("id"), "oldMeterId", row.get("old_meter_id"),
                "newMeterId", row.get("new_meter_id"), "evidenceNo", row.get("evidence_no"),
                "snapshotChecksum", row.get("snapshot_checksum"), "status", "COMPLETED", "replayed", replayed);
    }

    @Transactional
    public ChargeResult generateCharges(String batchId, ChargeRequest request) {
        requireWrite(request.communityId());
        Map<String, Object> batch = lockBatch(batchId, request.communityId());
        if (!"APPROVED".equals(text(batch.get("status")))) throw invalid("抄表批次审核后才能生成费用");
        YearMonth period = YearMonth.parse(text(batch.get("reading_period")));
        Map<String, Object> standard = meterStandard(request.communityId(), request.feeStandardId(), period.atDay(1));
        List<Map<String, Object>> readings = jdbc.queryForList("""
                SELECT mr.id reading_id, mr.meter_id, mr.billable_usage, mr.calculation_snapshot,
                       mr.calculation_checksum, m.asset_id, m.meter_no,
                       fa.id allocation_id, fa.coefficient, fa.version allocation_version
                FROM meter_reading mr JOIN meter m ON m.id=mr.meter_id
                LEFT JOIN fee_allocation fa ON fa.community_id=:communityId
                  AND fa.fee_standard_id=:standardId AND fa.target_type='METER'
                  AND fa.meter_id=m.id AND fa.status='ACTIVE' AND fa.effective_from<=:periodDate
                  AND (fa.effective_to IS NULL OR fa.effective_to>=:periodDate)
                WHERE mr.batch_id=:batchId AND mr.status='APPROVED' AND m.asset_id IS NOT NULL ORDER BY mr.id
                """, params("communityId", request.communityId(), "standardId", request.feeStandardId(),
                "periodDate", period.atDay(1), "batchId", batchId));
        if (readings.isEmpty()) throw invalid("该批次没有可计费的已审核分表读数");
        for (Map<String, Object> reading : readings) {
            if (reading.get("allocation_id") == null) {
                throw conflict("METER_FEE_ALLOCATION_MISSING",
                        "仪表 " + reading.get("meter_no") + " 在计费周期没有有效费用分配");
            }
        }
        int generated = 0, replayed = 0;
        LocalDateTime now = now();
        for (Map<String, Object> reading : readings) {
            String readingId = text(reading.get("reading_id"));
            Long exists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM bill_item WHERE source_type='METER_READING' AND source_id=:id
                    """, Map.of("id", readingId), Long.class);
            if (exists != null && exists > 0) {
                replayed++;
                continue;
            }
            BigDecimal quantity = decimal(reading.get("billable_usage"));
            BigDecimal coefficient = decimal(reading.get("coefficient"));
            BigDecimal amount = chargeAmount(reading, standard);
            String billId = findOrCreateBill(request.communityId(), text(reading.get("asset_id")), period, now);
            Map<String, Object> bill = jdbc.queryForMap("SELECT locked FROM bill WHERE id=:id FOR UPDATE",
                    Map.of("id", billId));
            if (Boolean.TRUE.equals(bill.get("locked"))) throw conflict("BILL_LOCKED", "账单已锁定，不能追加计量费用");
            Object originalSnapshot;
            try {
                originalSnapshot = objectMapper.readTree(text(reading.get("calculation_snapshot")));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Cannot parse original meter snapshot", exception);
            }
            String snapshot = json(ordered("formula", "billableUsage * unitPrice * allocationCoefficient",
                    "batchId", batchId, "readingId", readingId, "originalReadingSnapshot", originalSnapshot,
                    "originalReadingChecksum", reading.get("calculation_checksum"),
                    "feeStandardId", request.feeStandardId(), "feeStandardVersionId", standard.get("version_id"),
                    "feeStandardVersionNo", standard.get("version_no"), "feeAllocationId", reading.get("allocation_id"),
                    "feeAllocationVersion", reading.get("allocation_version"), "quantity", quantity,
                    "unitPrice", standard.get("unit_price"), "coefficient", coefficient,
                    "minimumAmount", standard.get("minimum_amount"), "maximumAmount", standard.get("maximum_amount"),
                    "roundingMode", standard.get("rounding_mode"), "decimalScale", standard.get("decimal_scale"),
                    "amount", amount, "assumptionRule", true));
            jdbc.update("""
                    INSERT INTO bill_item
                        (id, bill_id, fee_definition_id, fee_standard_version_id, fee_allocation_id,
                         item_name_snapshot, quantity, unit_price, coefficient, amount,
                         calculation_snapshot, source_type, source_id, created_at)
                    VALUES (:id, :billId, :definitionId, :versionId, :allocationId,
                            :name, :quantity, :unitPrice, :coefficient, :amount,
                            :snapshot, 'METER_READING', :readingId, :now)
                    """, params("id", UUID.randomUUID().toString(), "billId", billId,
                    "definitionId", standard.get("definition_id"), "versionId", standard.get("version_id"),
                    "allocationId", reading.get("allocation_id"), "name", standard.get("item_name"),
                    "quantity", quantity, "unitPrice", standard.get("unit_price"), "coefficient", coefficient,
                    "amount", amount, "snapshot", snapshot, "readingId", readingId, "now", now));
            jdbc.update("""
                    UPDATE bill SET original_amount=original_amount+:amount,
                        total_amount=total_amount+:amount, outstanding_amount=outstanding_amount+:amount,
                        status=CASE WHEN paid_amount=0 THEN 'UNPAID' ELSE 'PARTIAL' END,
                        version=version+1, updated_at=:now WHERE id=:id
                    """, Map.of("amount", amount, "now", now, "id", billId));
            generated++;
        }
        List<Map<String, Object>> metrics = writeReconciliation(batchId, request.communityId(), standard, readings);
        BigDecimal totalAmount = metric(metrics, "AMOUNT", "targetValue");
        boolean matched = metrics.stream().allMatch(row -> "MATCHED".equals(row.get("status")));
        event(request.communityId(), "CHARGE", batchId, "METER_CHARGES_GENERATED", "BATCH", batchId,
                ordered("feeStandardId", request.feeStandardId(), "generated", generated, "replayed", replayed,
                        "totalAmount", totalAmount, "reconciliationMatched", matched, "assumptionRule", true));
        audit.success(request.communityId(), "meter-charge:generate", "meter-reading-batch", batchId,
                Map.of("generated", generated, "replayed", replayed, "total", totalAmount, "matched", matched));
        return new ChargeResult(generated, replayed, totalAmount, true, matched, metrics);
    }

    public List<Map<String, Object>> reconciliation(String batchId, String communityId) {
        requireRead(communityId);
        requireBatch(batchId, communityId);
        return reconciliationRows(batchId);
    }

    private BigDecimal chargeAmount(Map<String, Object> reading, Map<String, Object> standard) {
        BigDecimal amount = decimal(reading.get("billable_usage"))
                .multiply(decimal(standard.get("unit_price"))).multiply(decimal(reading.get("coefficient")));
        if (standard.get("minimum_amount") != null) amount = amount.max(decimal(standard.get("minimum_amount")));
        if (standard.get("maximum_amount") != null) amount = amount.min(decimal(standard.get("maximum_amount")));
        return amount.setScale(number(standard.get("decimal_scale")),
                RoundingMode.valueOf(text(standard.get("rounding_mode"))));
    }

    private List<Map<String, Object>> writeReconciliation(String batchId, String communityId,
                                                           Map<String, Object> standard,
                                                           List<Map<String, Object>> readings) {
        BigDecimal sourceCount = BigDecimal.valueOf(readings.size());
        BigDecimal sourceUsage = readings.stream().map(row -> decimal(row.get("billable_usage")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, RoundingMode.HALF_UP);
        BigDecimal sourceAmount = readings.stream().map(row -> chargeAmount(row, standard))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4, RoundingMode.HALF_UP);
        Map<String, Object> targets = jdbc.queryForMap("""
                SELECT COUNT(*) item_count, COALESCE(SUM(bi.quantity),0) total_usage,
                       COALESCE(SUM(bi.amount),0) total_amount
                FROM bill_item bi JOIN meter_reading mr ON mr.id=bi.source_id
                WHERE bi.source_type='METER_READING' AND mr.batch_id=:batchId
                  AND bi.fee_standard_version_id=:versionId
                """, Map.of("batchId", batchId, "versionId", standard.get("version_id")));
        BigDecimal targetCount = decimal(targets.get("item_count"));
        BigDecimal targetUsage = decimal(targets.get("total_usage")).setScale(4, RoundingMode.HALF_UP);
        BigDecimal targetAmount = decimal(targets.get("total_amount")).setScale(4, RoundingMode.HALF_UP);
        upsertMetric(batchId, communityId, text(standard.get("standard_id")), "COUNT", sourceCount, targetCount,
                ordered("meaning", "approved asset readings versus generated bill items"));
        upsertMetric(batchId, communityId, text(standard.get("standard_id")), "USAGE", sourceUsage, targetUsage,
                ordered("meaning", "billable usage versus bill item quantity"));
        upsertMetric(batchId, communityId, text(standard.get("standard_id")), "AMOUNT", sourceAmount, targetAmount,
                ordered("meaning", "calculated amount versus bill item amount", "roundingMode", standard.get("rounding_mode")));
        return jdbc.queryForList("""
                SELECT metric_name metricName, source_value sourceValue, target_value targetValue,
                       difference_value differenceValue, status
                FROM meter_charge_reconciliation
                WHERE batch_id=:batchId AND fee_standard_id=:standardId ORDER BY metric_name
                """, Map.of("batchId", batchId, "standardId", standard.get("standard_id")));
    }

    private void upsertMetric(String batchId, String communityId, String standardId, String name,
                              BigDecimal source, BigDecimal target, Map<String, Object> detail) {
        BigDecimal difference = target.subtract(source).setScale(4, RoundingMode.HALF_UP);
        String status = difference.signum() == 0 ? "MATCHED" : "MISMATCH";
        jdbc.update("""
                INSERT INTO meter_charge_reconciliation
                    (id, community_id, batch_id, fee_standard_id, metric_name, source_value,
                     target_value, difference_value, status, detail_json, created_at)
                VALUES (:id, :communityId, :batchId, :standardId, :name, :source,
                        :target, :difference, :status, :detail, :now)
                ON DUPLICATE KEY UPDATE source_value=VALUES(source_value), target_value=VALUES(target_value),
                    difference_value=VALUES(difference_value), status=VALUES(status),
                    detail_json=VALUES(detail_json), created_at=VALUES(created_at)
                """, params("id", UUID.randomUUID().toString(), "communityId", communityId,
                "batchId", batchId, "standardId", standardId, "name", name, "source", source,
                "target", target, "difference", difference, "status", status,
                "detail", json(detail), "now", now()));
    }

    private Map<String, Object> meterStandard(String communityId, String standardId, LocalDate periodDate) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT fs.id standard_id, fd.id definition_id, fd.name item_name,
                       fd.decimal_scale, fd.rounding_mode, fsv.id version_id, fsv.version_no,
                       fsv.unit_price, fsv.minimum_amount, fsv.maximum_amount
                FROM fee_standard fs JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                JOIN fee_standard_version fsv ON fsv.fee_standard_id=fs.id
                WHERE fs.id=:standardId AND fs.community_id=:communityId
                  AND fd.fee_type='METER' AND fd.fee_class='USAGE'
                  AND fs.asset_type='METER' AND fs.calculation_basis='METER_USAGE'
                  AND fs.status='ACTIVE' AND fsv.status='ACTIVE'
                  AND fsv.effective_from<=:periodDate
                  AND (fsv.effective_to IS NULL OR fsv.effective_to>=:periodDate)
                ORDER BY fsv.version_no DESC LIMIT 1
                """, Map.of("standardId", standardId, "communityId", communityId, "periodDate", periodDate));
        if (rows.isEmpty()) throw invalid("计量费用标准不存在、未启用或在周期内无有效版本");
        return rows.get(0);
    }

    private String findOrCreateBill(String communityId, String assetId, YearMonth period, LocalDateTime now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, locked FROM bill
                WHERE community_id=:communityId AND asset_id=:assetId AND billing_period=:period FOR UPDATE
                """, Map.of("communityId", communityId, "assetId", assetId, "period", period.toString()));
        if (!rows.isEmpty()) {
            if (Boolean.TRUE.equals(rows.get(0).get("locked"))) throw conflict("BILL_LOCKED", "账单已锁定，不能追加计量费用");
            return text(rows.get(0).get("id"));
        }
        List<String> customers = jdbc.queryForList("""
                SELECT customer_id FROM customer_asset_relation WHERE asset_id=:assetId AND status='ACTIVE'
                ORDER BY primary_relation DESC, created_at LIMIT 1
                """, Map.of("assetId", assetId), String.class);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO bill
                    (id, community_id, asset_id, customer_id, bill_no, bill_type,
                     billing_period, charge_date, configuration_checksum, status,
                     original_amount, adjustment_amount, total_amount, paid_amount, outstanding_amount,
                     locked, lock_reason, due_date, version, created_at, updated_at)
                VALUES (:id, :communityId, :assetId, :customerId, :billNo, 'PERIODIC',
                        :period, :chargeDate, :checksum, 'UNPAID',
                        0, 0, 0, 0, 0, FALSE, NULL, :dueDate, 0, :now, :now)
                """, params("id", id, "communityId", communityId, "assetId", assetId,
                "customerId", customers.isEmpty() ? null : customers.get(0),
                "billNo", "MTR-" + period.toString().replace("-", "") + "-" + assetId,
                "period", period.toString(), "chargeDate", period.atDay(1),
                "checksum", sha256("meter|" + communityId + "|" + assetId + "|" + period),
                "dueDate", period.atEndOfMonth(), "now", now));
        return id;
    }

    private BigDecimal metric(List<Map<String, Object>> metrics, String name, String field) {
        return metrics.stream().filter(row -> name.equals(row.get("metricName")))
                .map(row -> decimal(row.get(field))).findFirst().orElse(BigDecimal.ZERO);
    }

    private void requireRead(String communityId) {
        security.requirePermission("meter:read");
        security.requireProject(communityId);
    }

    private void requireWrite(String communityId) {
        security.requirePermission("meter:write");
        security.requireProject(communityId);
    }

    private List<Map<String, Object>> reconciliationRows(String batchId) {
        return jdbc.queryForList("""
                SELECT id, batch_id batchId, fee_standard_id feeStandardId, metric_name metricName,
                       source_value sourceValue, target_value targetValue, difference_value differenceValue,
                       status, detail_json detail, created_at createdAt
                FROM meter_charge_reconciliation WHERE batch_id=:batchId ORDER BY metric_name
                """, Map.of("batchId", batchId));
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

    private Map<String, Object> requireMeter(String communityId, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM meter WHERE id=:id AND community_id=:communityId
                """, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("仪表不存在");
        return rows.get(0);
    }

    private void requireDraft(Map<String, Object> batch) {
        if (!"DRAFT".equals(text(batch.get("status")))) throw invalid("只有草稿批次可以执行当前操作");
    }

    private void refreshBatch(String batchId) {
        jdbc.update("""
                UPDATE meter_reading_batch mb SET
                    total_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id),
                    normal_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id AND mr.validation_status='NORMAL'),
                    anomaly_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id AND mr.validation_status<>'NORMAL'),
                    reviewed_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id AND mr.validation_status='REVIEWED'),
                    data_checksum=SHA2(CONCAT('draft-meter-batch|', mb.id, '|', mb.version, '|',
                        (SELECT COALESCE(GROUP_CONCAT(mr.calculation_checksum ORDER BY mr.meter_id SEPARATOR '|'),'')
                         FROM meter_reading mr WHERE mr.batch_id=mb.id)), 256),
                    version=version+1, updated_at=:now WHERE mb.id=:batchId
                """, Map.of("now", now(), "batchId", batchId));
    }

    private void event(String communityId, String aggregateType, String aggregateId, String eventType,
                       String referenceType, String referenceId, Map<String, Object> detail) {
        String detailJson = json(detail);
        jdbc.update("""
                INSERT INTO meter_event
                    (id, community_id, aggregate_type, aggregate_id, event_type, reference_type, reference_id,
                     detail_json, detail_checksum, actor_user_id, created_at)
                VALUES (:id, :communityId, :aggregateType, :aggregateId, :eventType, :referenceType, :referenceId,
                        :detail, :checksum, :userId, :now)
                """, params("id", UUID.randomUUID().toString(), "communityId", communityId,
                "aggregateType", aggregateType, "aggregateId", aggregateId, "eventType", eventType,
                "referenceType", referenceType, "referenceId", referenceId, "detail", detailJson,
                "checksum", sha256(detailJson), "userId", security.requirePrincipal().userId(), "now", now()));
    }

    private String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "计量写操作必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
        String normalized = key.trim();
        if (normalized.length() > 120) throw invalid("Idempotency-Key 最长为 120 个字符");
        return normalized;
    }

    private void requireSameHash(Object storedHash, String currentHash) {
        if (storedHash == null || !MessageDigest.isEqual(text(storedHash).getBytes(StandardCharsets.UTF_8),
                currentHash.getBytes(StandardCharsets.UTF_8))) {
            throw conflict("IDEMPOTENCY_REQUEST_CONFLICT", "相同 Idempotency-Key 已用于不同计量请求");
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

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal number ? number : new BigDecimal(String.valueOf(value));
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize meter snapshot", exception);
        }
    }

    private MapSqlParameterSource params(Object... pairs) {
        MapSqlParameterSource result = new MapSqlParameterSource();
        for (int index = 0; index < pairs.length; index += 2) result.addValue(text(pairs[index]), pairs[index + 1]);
        return result;
    }

    private Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put(text(pairs[index]), pairs[index + 1]);
        return result;
    }

    private Map<String, Object> withReplay(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("replayed", true);
        return result;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_METER_OPERATION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("METER_RECORD_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private BusinessException versionConflict() {
        return conflict("METER_VERSION_CONFLICT", "记录已被其他操作修改，请刷新后重试");
    }

    private record InsertOutcome(String readingId, boolean created, boolean anomaly) {}

    public record CreateBatch(@NotBlank String communityId, @NotBlank @Size(max = 100) String batchNo,
                              @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String readingPeriod,
                              @NotBlank @Pattern(regexp = "MANUAL|IOT_SIMULATOR|MIXED") String sourceType) {}
    public record ReadingInput(@NotBlank String meterId, @DecimalMin("0") BigDecimal previousReading,
                               @NotNull @DecimalMin("0") BigDecimal currentReading,
                               BigDecimal correction, @DecimalMin("0") BigDecimal allocatedShare,
                               LocalDateTime readingAt) {}
    public record ReadingsInput(@NotBlank String communityId, @NotBlank String batchId,
                                @NotEmpty List<@Valid ReadingInput> readings) {}
    public record InputResult(int created, int replayed, int anomalies) {}
    public record ReviewRequest(@NotBlank String communityId, @NotBlank @Size(max = 500) String reason,
                                long expectedVersion) {}
    public record ShareRequest(@NotBlank String communityId, @NotBlank String ruleId, @NotBlank String batchId,
                               @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal totalUsage) {}
    public record ShareLine(String assetId, String assetName, BigDecimal area, BigDecimal allocatedUsage) {}
    public record SharePreview(BigDecimal requestedTotal, BigDecimal totalArea, BigDecimal allocatedTotal,
                               boolean assumptionRule, String ruleVersionId, int ruleVersionNo,
                               List<ShareLine> items) {}
    public record ShareApplyResult(int inserted, int replayed, BigDecimal allocatedTotal, boolean assumptionRule,
                                   String ruleVersionId, int ruleVersionNo) {}
    public record ReplacementRequest(@NotBlank String communityId, @NotBlank @Size(max = 100) String newMeterNo,
                                     @NotNull @DecimalMin("0") BigDecimal oldFinalReading,
                                     @NotNull @DecimalMin("0") BigDecimal newInitialReading,
                                     @NotBlank @Size(max = 500) String reason) {}
    public record ChargeRequest(@NotBlank String communityId, @NotBlank String feeStandardId) {}
    public record ChargeResult(int generated, int replayed, BigDecimal totalAmount, boolean assumptionRule,
                               boolean reconciliationMatched, List<Map<String, Object>> reconciliation) {}
}
