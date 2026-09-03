package com.propertyops.pms.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class ReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final ReportQueryEngine engine;
    private final ReportJobProcessor processor;

    public ReportService(NamedParameterJdbcTemplate jdbc, SecurityContextService security, AuditService audit,
                         ObjectMapper objectMapper, ReportQueryEngine engine, ReportJobProcessor processor) {
        this.jdbc = jdbc; this.security = security; this.audit = audit; this.objectMapper = objectMapper;
        this.engine = engine; this.processor = processor;
    }

    public List<Map<String, Object>> catalog(String communityId) { return engine.catalog(communityId); }

    public ReportModels.ReportFilterOptions filterOptions(String communityId, String reportCode) {
        return engine.filterOptions(communityId, reportCode);
    }

    public Map<String, Object> query(String communityId, String code, Map<String, String> filters,
                                     List<String> columns, int page, int size) {
        return engine.execute(communityId, code, filters, columns, page, size);
    }

    @Transactional
    public Map<String, Object> createExport(ReportModels.CreateExportJob request, String idempotencyKey) {
        String code = request.reportCode().trim().toUpperCase(Locale.ROOT);
        write(request.communityId(), exportPermission(code));
        String key = requireKey(idempotencyKey);
        String format = exportFormat(request.format());
        // Validate and normalize before hashing, replay comparison and persistence so
        // the online query and the asynchronous artifact use the same filter contract.
        Map<String, Object> validation = engine.executeTrusted(request.communityId(), code, request.filters(), request.selectedColumns(), 1, 1);
        @SuppressWarnings("unchecked") Map<String, String> normalizedFilters =
                (Map<String, String>) validation.get("appliedFilters");
        @SuppressWarnings("unchecked") List<String> columns = (List<String>) validation.get("columns");
        String requestJson = json(Map.of("communityId", request.communityId(), "reportCode", code,
                "format", format, "filters", new TreeMap<>(normalizedFilters), "selectedColumns", columns));
        String hash = sha256(requestJson);
        Map<String, Object> replay = replay("report_export_job", request.communityId(), key);
        if (replay != null) { requireSame(replay, hash); return withoutBlob(replay); }
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String watermark = "合成验收环境 | " + security.requirePrincipal().username() + " | " + now + " | " + code;
        jdbc.update("""
                INSERT INTO report_export_job
                    (id,community_id,report_code,request_key,request_hash,export_format,filters_json,
                     selected_columns_json,watermark_text,status,requested_by,created_at)
                VALUES (:id,:communityId,:code,:key,:hash,:format,:filters,:columns,:watermark,'QUEUED',:userId,:now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("code", code).addValue("key", key).addValue("hash", hash).addValue("format", format)
                .addValue("filters", json(new TreeMap<>(normalizedFilters))).addValue("columns", json(columns))
                .addValue("watermark", watermark).addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        exportEvent(id, request.communityId(), "QUEUED", Map.of("format", format, "reportCode", code));
        audit.success(request.communityId(), "report-export:queue", "report-export-job", id,
                Map.of("reportCode", code, "format", format, "columns", columns));
        afterCommit(() -> processor.processExport(id));
        return exportJob(id, request.communityId());
    }

    public List<Map<String, Object>> exports(String communityId) {
        readAnyReport(communityId);
        return jdbc.queryForList("""
                SELECT id,community_id,report_code,export_format,status,row_count,artifact_name,artifact_mime,
                       artifact_checksum,error_message,requested_by,created_at,started_at,completed_at
                FROM report_export_job WHERE community_id=:communityId ORDER BY created_at DESC LIMIT 200
                """, Map.of("communityId", communityId)).stream()
                .filter(row -> canReadReport(String.valueOf(row.get("report_code"))))
                .toList();
    }

    public Map<String, Object> exportJob(String id, String communityId) {
        security.requireProject(communityId);
        Map<String, Object> row = required("""
                SELECT id,community_id,report_code,export_format,status,row_count,artifact_name,artifact_mime,
                       artifact_checksum,error_message,requested_by,created_at,started_at,completed_at
                FROM report_export_job WHERE id=:id AND community_id=:communityId
                """, Map.of("id", id, "communityId", communityId), "导出任务不存在");
        security.requirePermission(reportReadPermission(String.valueOf(row.get("report_code"))));
        return row;
    }

    @Transactional
    public Artifact exportArtifact(String id, String communityId) {
        security.requireProject(communityId);
        Map<String, Object> row = required("SELECT * FROM report_export_job WHERE id=:id AND community_id=:communityId FOR UPDATE",
                Map.of("id", id, "communityId", communityId), "导出任务不存在");
        security.requirePermission(exportPermission(String.valueOf(row.get("report_code"))));
        requireSucceeded(row);
        exportEvent(id, communityId, "DOWNLOADED", Map.of("artifactChecksum", row.get("artifact_checksum")));
        audit.success(communityId, "report-export:download", "report-export-job", id,
                Map.of("artifactChecksum", row.get("artifact_checksum"), "rowCount", row.get("row_count")));
        return new Artifact(String.valueOf(row.get("artifact_name")), String.valueOf(row.get("artifact_mime")),
                (byte[]) row.get("artifact_blob"));
    }

    @Transactional
    public Map<String, Object> createReceiptPrint(ReportModels.CreateReceiptPrintJob request, String idempotencyKey) {
        write(request.communityId(), "finance:print");
        String key = requireKey(idempotencyKey);
        String format = request.format().trim().toUpperCase(Locale.ROOT);
        if (!List.of("PDF", "PRINT").contains(format)) throw invalid("PRINT_FORMAT_INVALID", "票据打印只支持 PDF 或 PRINT");
        List<String> ids = request.receiptIds().stream().distinct().toList();
        String requestJson = json(Map.of("communityId", request.communityId(), "receiptIds", ids, "format", format));
        String hash = sha256(requestJson);
        Map<String, Object> replay = replay("receipt_print_job", request.communityId(), key);
        if (replay != null) { requireSame(replay, hash); return withoutBlob(replay); }
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", request.communityId()).addValue("ids", ids);
        List<Map<String, Object>> receipts = jdbc.queryForList("""
                SELECT id,receipt_no,status,template_version,data_snapshot,snapshot_checksum,issued_at
                FROM receipt WHERE community_id=:communityId AND id IN (:ids) ORDER BY issued_at,receipt_no
                """, params);
        if (receipts.size() != ids.size()) throw invalid("RECEIPT_SCOPE_INVALID", "所选收据不存在或不属于当前项目");
        if (receipts.stream().anyMatch(row -> !"ISSUED".equals(row.get("status"))))
            throw conflict("只有已签发收据可以批量打印");
        String id = UUID.randomUUID().toString(); LocalDateTime now = now();
        String watermark = "合成验收环境 | " + security.requirePrincipal().username() + " | " + now + " | RECEIPT";
        jdbc.update("""
                INSERT INTO receipt_print_job
                    (id,community_id,request_key,request_hash,output_format,template_version,watermark_text,
                     status,item_count,requested_by,created_at)
                VALUES (:id,:communityId,:key,:hash,:format,'receipt-v1',:watermark,'QUEUED',:count,:userId,:now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId()).addValue("key", key)
                .addValue("hash", hash).addValue("format", format).addValue("watermark", watermark)
                .addValue("count", receipts.size()).addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        for (int index = 0; index < receipts.size(); index++) {
            Map<String, Object> receipt = receipts.get(index);
            String snapshot = json(Map.of("receiptNo", receipt.get("receipt_no"), "status", receipt.get("status"),
                    "templateVersion", receipt.get("template_version"), "dataSnapshot", receipt.get("data_snapshot"),
                    "sourceChecksum", receipt.get("snapshot_checksum"), "issuedAt", receipt.get("issued_at")));
            jdbc.update("""
                    INSERT INTO receipt_print_item
                        (id,job_id,receipt_id,receipt_no_snapshot,receipt_snapshot,snapshot_checksum,sequence_no,created_at)
                    VALUES (:id,:jobId,:receiptId,:receiptNo,:snapshot,:checksum,:sequence,:now)
                    """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("jobId", id)
                    .addValue("receiptId", receipt.get("id")).addValue("receiptNo", receipt.get("receipt_no"))
                    .addValue("snapshot", snapshot).addValue("checksum", sha256(snapshot))
                    .addValue("sequence", index + 1).addValue("now", now));
        }
        audit.success(request.communityId(), "receipt-print:queue", "receipt-print-job", id,
                Map.of("itemCount", receipts.size(), "format", format));
        afterCommit(() -> processor.processReceiptPrint(id));
        return receiptPrintJob(id, request.communityId());
    }

    public List<Map<String, Object>> receiptPrints(String communityId) {
        readFinance(communityId);
        return jdbc.queryForList("""
                SELECT id,community_id,output_format,template_version,status,item_count,artifact_name,artifact_mime,
                       artifact_checksum,error_message,requested_by,created_at,started_at,completed_at
                FROM receipt_print_job WHERE community_id=:communityId ORDER BY created_at DESC LIMIT 200
                """, Map.of("communityId", communityId));
    }

    public Map<String, Object> receiptPrintJob(String id, String communityId) {
        readFinance(communityId);
        return required("""
                SELECT id,community_id,output_format,template_version,status,item_count,artifact_name,artifact_mime,
                       artifact_checksum,error_message,requested_by,created_at,started_at,completed_at
                FROM receipt_print_job WHERE id=:id AND community_id=:communityId
                """, Map.of("id", id, "communityId", communityId), "打印任务不存在");
    }

    public Artifact receiptArtifact(String id, String communityId) {
        write(communityId, "finance:print");
        Map<String, Object> row = required("SELECT * FROM receipt_print_job WHERE id=:id AND community_id=:communityId",
                Map.of("id", id, "communityId", communityId), "打印任务不存在");
        requireSucceeded(row);
        return new Artifact(String.valueOf(row.get("artifact_name")), String.valueOf(row.get("artifact_mime")),
                (byte[]) row.get("artifact_blob"));
    }

    @Transactional
    public Map<String, Object> createNotification(ReportModels.CreateNotificationBatch request, String idempotencyKey) {
        write(request.communityId(), "notification:send");
        String key = requireKey(idempotencyKey);
        String channel = request.channel().trim().toUpperCase(Locale.ROOT);
        if (!List.of("SMS_SIMULATOR", "WECHAT_SIMULATOR", "EMAIL_SIMULATOR").contains(channel))
            throw invalid("NOTIFICATION_CHANNEL_INVALID", "通知渠道必须是显式模拟通道");
        List<String> explicit = request.billIds().stream().distinct().toList();
        String requestJson = json(Map.of("communityId", request.communityId(), "billingPeriod", request.billingPeriod(),
                "channel", channel, "billIds", explicit, "contentTemplate", request.contentTemplate()));
        String hash = sha256(requestJson);
        Map<String, Object> replay = replay("notification_batch", request.communityId(), key);
        if (replay != null) { requireSame(replay, hash); return replay; }
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", request.communityId())
                .addValue("period", request.billingPeriod()).addValue("ids", explicit.isEmpty() ? List.of("__NONE__") : explicit);
        String selected = explicit.isEmpty() ? "" : " AND b.id IN (:ids)";
        List<Map<String, Object>> bills = jdbc.queryForList("""
                SELECT b.id,b.bill_no,b.customer_id,b.outstanding_amount,a.display_name asset_name,
                       COALESCE(c.mobile_masked,'***') masked_recipient
                FROM bill b JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id
                WHERE b.community_id=:communityId AND b.billing_period=:period AND b.outstanding_amount>0
                """ + selected + " ORDER BY b.bill_no LIMIT 1000", params);
        if (!explicit.isEmpty() && bills.size() != explicit.size()) throw invalid("NOTIFICATION_BILL_SCOPE_INVALID", "所选账单不存在、已结清或不属于当前项目");
        if (bills.isEmpty()) throw invalid("NOTIFICATION_EMPTY", "当前账期没有可通知的未结清账单");
        String id = UUID.randomUUID().toString(); LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO notification_batch
                    (id,community_id,request_key,request_hash,batch_no,billing_period,channel,status,simulated,
                     selected_bill_count,success_count,failed_count,content_template,requested_by,created_at,completed_at)
                VALUES (:id,:communityId,:key,:hash,:batchNo,:period,:channel,'SUCCEEDED',TRUE,
                        :count,:count,0,:template,:userId,:now,:now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId()).addValue("key", key)
                .addValue("hash", hash).addValue("batchNo", "SIM-NOTIFY-" + id).addValue("period", request.billingPeriod())
                .addValue("channel", channel).addValue("count", bills.size()).addValue("template", request.contentTemplate())
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        for (Map<String, Object> bill : bills) {
            String content = request.contentTemplate().replace("{billNo}", String.valueOf(bill.get("bill_no")))
                    .replace("{amount}", String.valueOf(bill.get("outstanding_amount")))
                    .replace("{asset}", String.valueOf(bill.get("asset_name")));
            jdbc.update("""
                    INSERT INTO notification_message
                        (id,batch_id,bill_id,customer_id,masked_recipient,content_snapshot,content_checksum,
                         status,simulated_reference,sent_at,created_at)
                    VALUES (:id,:batchId,:billId,:customerId,:recipient,:content,:checksum,
                            'SENT_SIMULATED',:reference,:now,:now)
                    """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("batchId", id)
                    .addValue("billId", bill.get("id")).addValue("customerId", bill.get("customer_id"))
                    .addValue("recipient", bill.get("masked_recipient")).addValue("content", content)
                    .addValue("checksum", sha256(content)).addValue("reference", "SIM-MSG-" + UUID.randomUUID())
                    .addValue("now", now));
        }
        audit.success(request.communityId(), "notification:send-simulated", "notification-batch", id,
                Map.of("billingPeriod", request.billingPeriod(), "channel", channel, "messageCount", bills.size(), "simulated", true));
        return notification(id, request.communityId());
    }

    public List<Map<String, Object>> notifications(String communityId) {
        readNotifications(communityId);
        return jdbc.queryForList("""
                SELECT id,community_id,batch_no,billing_period,channel,status,simulated,selected_bill_count,
                       success_count,failed_count,requested_by,created_at,completed_at
                FROM notification_batch WHERE community_id=:communityId ORDER BY created_at DESC LIMIT 200
                """, Map.of("communityId", communityId));
    }

    public Map<String, Object> notification(String id, String communityId) {
        readNotifications(communityId);
        Map<String, Object> batch = required("""
                SELECT id,community_id,batch_no,billing_period,channel,status,simulated,selected_bill_count,
                       success_count,failed_count,requested_by,created_at,completed_at
                FROM notification_batch WHERE id=:id AND community_id=:communityId
                """, Map.of("id", id, "communityId", communityId), "通知批次不存在");
        batch.put("messages", jdbc.queryForList("""
                SELECT nm.id,nm.bill_id,nm.masked_recipient,nm.content_snapshot,nm.content_checksum,nm.status,
                       nm.simulated_reference,nm.sent_at,a.display_name asset_name,b.bill_no
                FROM notification_message nm JOIN bill b ON b.id=nm.bill_id JOIN asset a ON a.id=b.asset_id
                WHERE nm.batch_id=:id ORDER BY nm.created_at
                """, Map.of("id", id)));
        return batch;
    }

    private void exportEvent(String jobId, String communityId, String type, Object detail) {
        String detailJson = json(detail);
        jdbc.update("""
                INSERT INTO report_export_event
                    (id,community_id,job_id,event_type,detail_json,detail_checksum,actor_user_id,created_at)
                VALUES (:id,:communityId,:jobId,:type,:detail,:checksum,:actor,:now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("communityId", communityId)
                .addValue("jobId", jobId).addValue("type", type).addValue("detail", detailJson)
                .addValue("checksum", sha256(detailJson)).addValue("actor", security.requirePrincipal().userId()).addValue("now", now()));
    }

    private Map<String, Object> replay(String table, String communityId, String key) {
        if (!List.of("report_export_job", "receipt_print_job", "notification_batch").contains(table)) throw new IllegalArgumentException(table);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table + " WHERE community_id=:communityId AND request_key=:key FOR UPDATE",
                Map.of("communityId", communityId, "key", key));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireSame(Map<String, Object> replay, String hash) {
        if (!hash.equals(String.valueOf(replay.get("request_hash")))) throw conflict("幂等键已用于不同请求");
    }

    private Map<String, Object> withoutBlob(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source); result.remove("artifact_blob"); return result;
    }

    private void requireSucceeded(Map<String, Object> row) {
        if (!"SUCCEEDED".equals(row.get("status")) || row.get("artifact_blob") == null) throw conflict("任务尚未生成可下载制品");
    }

    private Map<String, Object> required(String sql, Map<String, ?> params, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) throw new BusinessException("RESOURCE_NOT_FOUND", message, HttpStatus.NOT_FOUND);
        return new LinkedHashMap<>(rows.get(0));
    }

    private String exportFormat(String value) {
        String result = value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("CSV", "XLSX", "PDF", "PRINT").contains(result)) throw invalid("EXPORT_FORMAT_INVALID", "导出格式必须为 CSV、XLSX、PDF 或 PRINT");
        return result;
    }

    private String exportPermission(String code) {
        if (java.util.Set.of("COLLECTION_RATE", "ARREARS_CLEARANCE_RATE", "COMPREHENSIVE_QUERY", "COLLECTION_CLEARANCE_SUMMARY",
                "CHARGE_DETAILS", "DISCOUNT_DETAILS", "PREPAYMENTS", "FEE_STATUS").contains(code)) return "report:export";
        if (java.util.Set.of("BILL_NOTIFICATIONS", "REMINDERS").contains(code)) return "notification:export";
        if ("OWNERSHIP_TRANSFERS".equals(code)) return "property:export-sensitive";
        if ("INVOICE_STATISTICS".equals(code)) return "invoice:export";
        if ("BANK_TRUST".equals(code)) return "bank:export";
        return "finance:export";
    }

    private String reportReadPermission(String code) {
        if (Set.of("COLLECTION_RATE", "ARREARS_CLEARANCE_RATE", "COMPREHENSIVE_QUERY", "COLLECTION_CLEARANCE_SUMMARY",
                "CHARGE_DETAILS", "DISCOUNT_DETAILS", "PREPAYMENTS", "FEE_STATUS").contains(code)) return "report:read";
        if (Set.of("BILL_NOTIFICATIONS", "REMINDERS").contains(code)) return "notification:read";
        if ("OWNERSHIP_TRANSFERS".equals(code)) return "property:read";
        if ("INVOICE_STATISTICS".equals(code)) return "invoice:read";
        if ("BANK_TRUST".equals(code)) return "bank:read";
        return "finance:read";
    }

    private boolean canReadReport(String code) {
        return security.requirePrincipal().permissions().contains(reportReadPermission(code));
    }

    private String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) throw invalid("IDEMPOTENCY_KEY_REQUIRED", "必须提供合法的 Idempotency-Key");
        return value.trim();
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }

    private void readAnyReport(String communityId) {
        security.requireAnyPermission("finance:read", "report:read", "notification:read", "property:read", "invoice:read", "bank:read");
        security.requireProject(communityId);
    }
    private void readFinance(String communityId) { security.requirePermission("finance:read"); security.requireProject(communityId); }
    private void readNotifications(String communityId) { security.requirePermission("notification:read"); security.requireProject(communityId); }
    private void write(String communityId, String permission) { security.requirePermission(permission); security.requireProject(communityId); }
    private BusinessException invalid(String code, String message) { return new BusinessException(code, message, HttpStatus.UNPROCESSABLE_ENTITY); }
    private BusinessException conflict(String message) { return new BusinessException("REPORT_CONFLICT", message, HttpStatus.CONFLICT); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public record Artifact(String name, String mime, byte[] bytes) {}
}
