package com.propertyops.pms.fee;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReceivableJobProcessor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ReceivableJobProcessor(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Async("receivableTaskExecutor")
    public void process(String jobId) {
        try {
            transactionTemplate.executeWithoutResult(status -> processTransaction(jobId));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> recoverFailedJob(jobId, exception));
        }
    }

    private void processTransaction(String jobId) {
        List<Map<String, Object>> jobs = jdbc.queryForList("""
                SELECT * FROM receivable_generation_job WHERE id=:id FOR UPDATE
                """, Map.of("id", jobId));
        if (jobs.isEmpty()) return;
        Map<String, Object> job = jobs.get(0);
        String status = text(job.get("status"));
        if (List.of("COMPLETED", "PARTIAL", "FAILED").contains(status)) return;
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE receivable_generation_job
                SET status='RUNNING', started_at=COALESCE(started_at,:now),
                    version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("now", now, "id", jobId));

        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT * FROM receivable_generation_item
                WHERE job_id=:id AND status='PENDING' ORDER BY row_no FOR UPDATE
                """, Map.of("id", jobId));
        String jobType = text(job.get("job_type"));
        if ("PERIODIC".equals(jobType)) {
            Map<String, List<Map<String, Object>>> byAsset = items.stream().collect(Collectors.groupingBy(
                    row -> text(row.get("asset_id")), LinkedHashMap::new, Collectors.toList()));
            for (List<Map<String, Object>> group : byAsset.values()) processGroup(job, group, true);
        } else if (!items.isEmpty()) {
            processGroup(job, items, false);
        }
        finish(job);
    }

    private void processGroup(Map<String, Object> job, List<Map<String, Object>> items, boolean periodic) {
        String jobId = text(job.get("id"));
        String communityId = text(job.get("community_id"));
        String billingPeriod = text(job.get("billing_period"));
        String assetId = text(items.get(0).get("asset_id"));
        List<Map<String, Object>> snapshots = items.stream().map(row -> snapshot(row.get("snapshot_json"))).toList();
        String checksum = sha256(snapshots.stream().map(row -> text(row.get("configurationChecksum")))
                .sorted(Comparator.naturalOrder()).collect(Collectors.joining("|")));

        if (periodic) {
            List<Map<String, Object>> existing = jdbc.queryForList("""
                    SELECT id, configuration_checksum FROM bill
                    WHERE community_id=:communityId AND asset_id=:assetId
                      AND billing_period=:period AND bill_type='PERIODIC' FOR UPDATE
                    """, Map.of("communityId", communityId, "assetId", assetId, "period", billingPeriod));
            if (!existing.isEmpty()) {
                Map<String, Object> bill = existing.get(0);
                if (checksum.equals(text(bill.get("configuration_checksum")))) {
                    updateItems(items, "SKIPPED", text(bill.get("id")), null, null);
                } else {
                    failItems(jobId, items, "PERIODIC_BILL_CONFIGURATION_CONFLICT",
                            "相同资产与账期已有不同配置快照的周期账单");
                }
                return;
            }
        }

        String billId = UUID.randomUUID().toString();
        BigDecimal total = items.stream().map(row -> decimal(row.get("amount"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> first = snapshots.get(0);
        Object customerId = items.stream().map(row -> row.get("customer_id")).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        LocalDate chargeDate = LocalDate.parse(text(first.get("chargeDate")));
        LocalDate dueDate = LocalDate.parse(text(first.get("dueDate")));
        String billNo = (periodic ? "RCV" : "TMP") + "-" + billingPeriod.replace("-", "") + "-"
                + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO bill
                    (id, community_id, receivable_job_id, asset_id, customer_id, bill_no, bill_type,
                     billing_period, charge_date, configuration_checksum, status,
                     total_amount, paid_amount, outstanding_amount, due_date,
                     version, created_at, updated_at)
                VALUES (:id, :communityId, :jobId, :assetId, :customerId, :billNo, :billType,
                        :period, :chargeDate, :checksum, 'UNPAID',
                        :total, 0, :total, :dueDate, 0, :now, :now)
                """, new MapSqlParameterSource("id", billId).addValue("communityId", communityId)
                .addValue("jobId", jobId).addValue("assetId", assetId).addValue("customerId", customerId)
                .addValue("billNo", billNo).addValue("billType", periodic ? "PERIODIC" : "TEMPORARY")
                .addValue("period", billingPeriod).addValue("chargeDate", chargeDate).addValue("checksum", checksum)
                .addValue("total", total).addValue("dueDate", dueDate).addValue("now", now));

        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> item = items.get(index);
            Map<String, Object> snapshot = snapshots.get(index);
            String sourceType = periodic ? "PERIODIC_ALLOCATION" : "TEMPORARY_JOB_LINE";
            String sourceId = periodic
                    ? billingPeriod + "|" + text(item.get("fee_allocation_id"))
                    : jobId + "|" + integer(item.get("row_no"));
            jdbc.update("""
                    INSERT INTO bill_item
                        (id, bill_id, fee_definition_id, fee_standard_version_id, fee_allocation_id,
                         item_name_snapshot, quantity, unit_price, coefficient, amount,
                         calculation_snapshot, source_type, source_id, created_at)
                    VALUES (:id, :billId, :definitionId, :standardVersionId, :allocationId,
                            :itemName, :quantity, :unitPrice, :coefficient, :amount,
                            :snapshot, :sourceType, :sourceId, :now)
                    """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("billId", billId)
                    .addValue("definitionId", item.get("fee_definition_id"))
                    .addValue("standardVersionId", item.get("fee_standard_version_id"))
                    .addValue("allocationId", item.get("fee_allocation_id"))
                    .addValue("itemName", item.get("item_name")).addValue("quantity", snapshot.get("quantity"))
                    .addValue("unitPrice", snapshot.get("unitPrice")).addValue("coefficient", snapshot.get("coefficient"))
                    .addValue("amount", item.get("amount")).addValue("snapshot", json(snapshot))
                    .addValue("sourceType", sourceType).addValue("sourceId", sourceId).addValue("now", now));
        }
        updateItems(items, "GENERATED", billId, null, null);
    }

    private void failItems(String jobId, List<Map<String, Object>> items, String code, String message) {
        updateItems(items, "FAILED", null, code, message);
        LocalDateTime now = now();
        for (Map<String, Object> item : items) {
            jdbc.update("""
                    INSERT INTO receivable_generation_error
                        (id, job_id, row_no, asset_id, fee_standard_id,
                         error_code, error_message, detail_json, created_at)
                    VALUES (:id, :jobId, :rowNo, :assetId, NULL, :code, :message, :detail, :now)
                    """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("jobId", jobId)
                    .addValue("rowNo", item.get("row_no")).addValue("assetId", item.get("asset_id"))
                    .addValue("code", code).addValue("message", safe(message))
                    .addValue("detail", json(Map.of("phase", "GENERATION"))).addValue("now", now));
        }
    }

    private void updateItems(List<Map<String, Object>> items, String status, String billId,
                             String errorCode, String errorMessage) {
        LocalDateTime now = now();
        for (Map<String, Object> item : items) {
            jdbc.update("""
                    UPDATE receivable_generation_item
                    SET status=:status, bill_id=:billId, error_code=:errorCode,
                        error_message=:errorMessage, updated_at=:now
                    WHERE id=:id
                    """, new MapSqlParameterSource("status", status).addValue("billId", billId)
                    .addValue("errorCode", errorCode).addValue("errorMessage", errorMessage)
                    .addValue("now", now).addValue("id", item.get("id")));
        }
    }

    private void finish(Map<String, Object> job) {
        String jobId = text(job.get("id"));
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) item_count,
                       SUM(CASE WHEN status='GENERATED' THEN 1 ELSE 0 END) generated_count,
                       SUM(CASE WHEN status='SKIPPED' THEN 1 ELSE 0 END) skipped_count,
                       SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END) failed_count,
                       COALESCE(SUM(CASE WHEN status IN ('GENERATED','SKIPPED') THEN amount ELSE 0 END),0) successful_amount
                FROM receivable_generation_item WHERE job_id=:id
                """, Map.of("id", jobId));
        int generated = integer(counts.get("generated_count"));
        int skipped = integer(counts.get("skipped_count"));
        int failed = integer(counts.get("failed_count"));
        int errors = integer(jdbc.queryForObject("SELECT COUNT(*) FROM receivable_generation_error WHERE job_id=:id",
                Map.of("id", jobId), Long.class));
        int successful = generated + skipped;
        String terminal = errors == 0 && failed == 0 ? "COMPLETED" : successful > 0 ? "PARTIAL" : "FAILED";
        reconcile(job, integer(counts.get("item_count")), successful, decimal(counts.get("successful_amount")));
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE receivable_generation_job
                SET status=:status, generated_count=:generated, skipped_count=:skipped,
                    error_count=:errors, completed_at=:now, version=version+1, updated_at=:now
                WHERE id=:id
                """, Map.of("status", terminal, "generated", generated, "skipped", skipped,
                "errors", errors, "now", now, "id", jobId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", jobId);
        payload.put("jobType", job.get("job_type"));
        payload.put("status", terminal);
        payload.put("generatedCount", generated);
        payload.put("skippedCount", skipped);
        payload.put("errorCount", errors);
        jdbc.update("""
                INSERT INTO outbox_event
                    (id, aggregate_type, aggregate_id, event_type, payload_json,
                     status, available_at, retry_count, created_at)
                VALUES (:id, 'RECEIVABLE_JOB', :jobId, 'ReceivableJobCompleted', :payload,
                        'PENDING', :now, 0, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "jobId", jobId, "payload", json(payload), "now", now));
        jdbc.update("""
                INSERT INTO audit_event
                    (id, actor_user_id, community_id, action_code, resource_type, resource_id,
                     request_id, result_status, detail_json, occurred_at)
                VALUES (:id, :actor, :communityId, 'receivable-job:complete',
                        'receivable-job', :jobId, NULL, 'SUCCESS', :detail, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString())
                .addValue("actor", job.get("requested_by")).addValue("communityId", job.get("community_id"))
                .addValue("jobId", jobId).addValue("detail", json(payload)).addValue("now", now));
    }

    private void reconcile(Map<String, Object> job, int itemCount, int successfulCount, BigDecimal successfulAmount) {
        String jobId = text(job.get("id"));
        String jobType = text(job.get("job_type"));
        String period = text(job.get("billing_period"));
        BigDecimal linkedItemAmount = jdbc.queryForObject("""
                SELECT COALESCE(SUM(bi.amount),0)
                FROM receivable_generation_item ri
                JOIN bill_item bi ON bi.bill_id=ri.bill_id
                  AND ((:jobType='PERIODIC' AND bi.source_type='PERIODIC_ALLOCATION'
                        AND bi.source_id=CONCAT(:period,'|',ri.fee_allocation_id))
                    OR (:jobType='TEMPORARY' AND bi.source_type='TEMPORARY_JOB_LINE'
                        AND bi.source_id=CONCAT(:jobId,'|',ri.row_no)))
                WHERE ri.job_id=:jobId AND ri.status IN ('GENERATED','SKIPPED')
                """, Map.of("jobType", jobType, "period", period, "jobId", jobId), BigDecimal.class);
        BigDecimal billTotal = jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.total_amount),0) FROM bill b
                WHERE b.id IN (SELECT DISTINCT bill_id FROM receivable_generation_item
                               WHERE job_id=:jobId AND bill_id IS NOT NULL)
                """, Map.of("jobId", jobId), BigDecimal.class);
        BigDecimal billItemTotal = jdbc.queryForObject("""
                SELECT COALESCE(SUM(bi.amount),0) FROM bill_item bi
                WHERE bi.bill_id IN (SELECT DISTINCT bill_id FROM receivable_generation_item
                                    WHERE job_id=:jobId AND bill_id IS NOT NULL)
                """, Map.of("jobId", jobId), BigDecimal.class);
        insertMetric(jobId, "ELIGIBLE_ITEM_COUNT", BigDecimal.valueOf(itemCount),
                BigDecimal.valueOf(successfulCount), Map.of("meaning", "configuration items vs generated or replayed items"));
        insertMetric(jobId, "SUCCESSFUL_ITEM_AMOUNT", successfulAmount,
                linkedItemAmount == null ? BigDecimal.ZERO : linkedItemAmount,
                Map.of("meaning", "job snapshot amount vs source-linked bill item amount"));
        insertMetric(jobId, "BILL_TOTAL_AMOUNT", billTotal == null ? BigDecimal.ZERO : billTotal,
                billItemTotal == null ? BigDecimal.ZERO : billItemTotal,
                Map.of("meaning", "bill header amount vs all bill item amount"));
    }

    private void insertMetric(String jobId, String name, BigDecimal source, BigDecimal target, Object detail) {
        BigDecimal difference = target.subtract(source);
        jdbc.update("""
                INSERT INTO receivable_generation_reconciliation
                    (id, job_id, metric_name, source_value, target_value,
                     difference_value, status, detail_json, created_at)
                VALUES (:id, :jobId, :name, :source, :target, :difference,
                        :status, :detail, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("jobId", jobId)
                .addValue("name", name).addValue("source", source).addValue("target", target)
                .addValue("difference", difference).addValue("status", difference.signum() == 0 ? "MATCHED" : "MISMATCH")
                .addValue("detail", json(detail)).addValue("now", now()));
    }

    private void recoverFailedJob(String jobId, RuntimeException exception) {
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT * FROM receivable_generation_job WHERE id=:id FOR UPDATE",
                Map.of("id", jobId));
        if (jobs.isEmpty() || List.of("COMPLETED", "PARTIAL", "FAILED").contains(text(jobs.get(0).get("status")))) return;
        String message = safe(exception.getMessage());
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO receivable_generation_error
                    (id, job_id, error_code, error_message, detail_json, created_at)
                VALUES (:id, :jobId, 'UNEXPECTED_GENERATION_FAILURE', :message, :detail, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "jobId", jobId, "message", message,
                "detail", json(Map.of("exception", exception.getClass().getSimpleName())), "now", now));
        jdbc.update("""
                UPDATE receivable_generation_job
                SET status='FAILED', error_count=(SELECT COUNT(*) FROM receivable_generation_error e WHERE e.job_id=:id),
                    completed_at=:now, version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("id", jobId, "now", now));
    }

    private Map<String, Object> snapshot(Object value) {
        try {
            return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot parse receivable snapshot", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize receivable evidence", exception);
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

    private int integer(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String safe(String value) {
        String text = value == null ? "generation failed" : value;
        return text.substring(0, Math.min(text.length(), 480));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
