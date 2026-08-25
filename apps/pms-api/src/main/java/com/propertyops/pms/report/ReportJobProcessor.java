package com.propertyops.pms.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Component
public class ReportJobProcessor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportQueryEngine engine;
    private final ReportArtifactWriter writer;
    private final ObjectMapper objectMapper;

    public ReportJobProcessor(NamedParameterJdbcTemplate jdbc, ReportQueryEngine engine,
                              ReportArtifactWriter writer, ObjectMapper objectMapper) {
        this.jdbc = jdbc; this.engine = engine; this.writer = writer; this.objectMapper = objectMapper;
    }

    @Async("reportTaskExecutor")
    @Transactional
    public void processExport(String jobId) {
        Map<String, Object> job = required("SELECT * FROM report_export_job WHERE id=:id FOR UPDATE", jobId);
        if (!"QUEUED".equals(job.get("status"))) return;
        LocalDateTime now = now();
        jdbc.update("UPDATE report_export_job SET status='RUNNING',started_at=:now WHERE id=:id",
                Map.of("now", now, "id", jobId));
        event(job, "STARTED", Map.of("reportCode", job.get("report_code")));
        try {
            Map<String, String> filters = read(String.valueOf(job.get("filters_json")), new TypeReference<>() {});
            List<String> columns = read(String.valueOf(job.get("selected_columns_json")), new TypeReference<>() {});
            Map<String, Object> result = engine.executeTrusted(String.valueOf(job.get("community_id")),
                    String.valueOf(job.get("report_code")), filters, columns, 1, 10_000);
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
            @SuppressWarnings("unchecked") List<String> actualColumns = (List<String>) result.get("columns");
            String format = String.valueOf(job.get("export_format"));
            ReportArtifactWriter.Artifact artifact = writer.write(format, String.valueOf(result.get("title")),
                    String.valueOf(job.get("watermark_text")), actualColumns, rows);
            String name = String.valueOf(job.get("report_code")).toLowerCase() + "-" + jobId + "." + artifact.extension();
            String checksum = sha256(artifact.bytes());
            jdbc.update("""
                    UPDATE report_export_job SET status='SUCCEEDED',row_count=:count,artifact_name=:name,
                        artifact_mime=:mime,artifact_blob=:bytes,artifact_checksum=:checksum,completed_at=:now
                    WHERE id=:id
                    """, new MapSqlParameterSource("count", rows.size()).addValue("name", name)
                    .addValue("mime", artifact.mime()).addValue("bytes", artifact.bytes())
                    .addValue("checksum", checksum).addValue("now", now()).addValue("id", jobId));
            event(job, "SUCCEEDED", Map.of("rowCount", rows.size(), "artifactChecksum", checksum));
        } catch (RuntimeException exception) {
            String message = safeError(exception);
            jdbc.update("UPDATE report_export_job SET status='FAILED',error_message=:error,completed_at=:now WHERE id=:id",
                    Map.of("error", message, "now", now(), "id", jobId));
            event(job, "FAILED", Map.of("error", message));
        }
    }

    @Async("reportTaskExecutor")
    @Transactional
    public void processReceiptPrint(String jobId) {
        Map<String, Object> job = required("SELECT * FROM receipt_print_job WHERE id=:id FOR UPDATE", jobId);
        if (!"QUEUED".equals(job.get("status"))) return;
        jdbc.update("UPDATE receipt_print_job SET status='RUNNING',started_at=:now WHERE id=:id",
                Map.of("now", now(), "id", jobId));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT rpi.receipt_no_snapshot receiptNo,rpi.receipt_snapshot receiptSnapshot,
                           rpi.snapshot_checksum snapshotChecksum,rpi.sequence_no sequenceNo
                    FROM receipt_print_item rpi WHERE rpi.job_id=:id ORDER BY rpi.sequence_no
                    """, Map.of("id", jobId));
            String format = String.valueOf(job.get("output_format"));
            ReportArtifactWriter.Artifact artifact = writer.write(format, "批量收据打印",
                    String.valueOf(job.get("watermark_text")),
                    List.of("receiptNo", "receiptSnapshot", "snapshotChecksum", "sequenceNo"), rows);
            String name = "receipt-batch-" + jobId + "." + artifact.extension();
            String checksum = sha256(artifact.bytes());
            jdbc.update("""
                    UPDATE receipt_print_job SET status='SUCCEEDED',artifact_name=:name,artifact_mime=:mime,
                        artifact_blob=:bytes,artifact_checksum=:checksum,completed_at=:now WHERE id=:id
                    """, new MapSqlParameterSource("name", name).addValue("mime", artifact.mime())
                    .addValue("bytes", artifact.bytes()).addValue("checksum", checksum)
                    .addValue("now", now()).addValue("id", jobId));
            jdbc.update("""
                    UPDATE receipt r JOIN receipt_print_item rpi ON rpi.receipt_id=r.id
                    SET r.print_count=r.print_count+1,r.last_printed_at=:now WHERE rpi.job_id=:id
                    """, Map.of("now", now(), "id", jobId));
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE receipt_print_job SET status='FAILED',error_message=:error,completed_at=:now WHERE id=:id",
                    Map.of("error", safeError(exception), "now", now(), "id", jobId));
        }
    }

    private void event(Map<String, Object> job, String type, Object detail) {
        String json = json(detail);
        jdbc.update("""
                INSERT INTO report_export_event
                    (id,community_id,job_id,event_type,detail_json,detail_checksum,actor_user_id,created_at)
                VALUES (:id,:communityId,:jobId,:type,:detail,:checksum,:actor,:now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString())
                .addValue("communityId", job.get("community_id")).addValue("jobId", job.get("id"))
                .addValue("type", type).addValue("detail", json).addValue("checksum", sha256(json.getBytes(StandardCharsets.UTF_8)))
                .addValue("actor", job.get("requested_by")).addValue("now", now()));
    }

    private Map<String, Object> required(String sql, String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("id", id));
        if (rows.isEmpty()) throw new IllegalStateException("Job not found");
        return rows.get(0);
    }

    private <T> T read(String json, TypeReference<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String safeError(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null ? exception.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 1000));
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
