package com.propertyops.pms.adapter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
public class IntegrationGovernanceService {
    private static final int MAX_CALLBACK_BYTES = 1_000_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SecurityContextService security;
    private final AuditService audit;
    private final CallbackSignatureService signatures;
    private final IntegrationProperties properties;
    private final PaymentAdapter payment;
    private final InvoiceAdapter invoice;
    private final BankTrustAdapter bank;
    private final IotAdapter iot;
    private final Java110Adapter java110;

    public IntegrationGovernanceService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                        SecurityContextService security, AuditService audit,
                                        CallbackSignatureService signatures, IntegrationProperties properties,
                                        PaymentAdapter payment, InvoiceAdapter invoice, BankTrustAdapter bank,
                                        IotAdapter iot, Java110Adapter java110) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.security = security;
        this.audit = audit;
        this.signatures = signatures;
        this.properties = properties;
        this.payment = payment;
        this.invoice = invoice;
        this.bank = bank;
        this.iot = iot;
        this.java110 = java110;
    }

    public Map<String, Object> workbench(String communityId) {
        security.requirePermission("integration:read");
        security.requireProject(communityId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("communityId", communityId);
        result.put("adapters", jdbc.queryForList("""
                SELECT adapter_code adapterCode,adapter_type providerType,display_name providerName,
                       mode,enabled,production_ready productionReady,endpoint_masked endpointMasked,
                       credential_status credentialStatus,signing_required signingRequired,
                       timeout_ms timeoutMs,max_attempts maxAttempts,retry_base_seconds retryBaseSeconds,
                       last_checked_at lastCheckedAt
                FROM integration_adapter_policy ORDER BY adapter_type
                """, Map.of()));
        result.put("outboxSummary", jdbc.queryForList("""
                SELECT status,COUNT(*) itemCount FROM outbox_event
                WHERE aggregate_type='INTEGRATION_SIMULATOR'
                  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.communityId'))=:communityId
                GROUP BY status ORDER BY status
                """, Map.of("communityId", communityId)));
        result.put("callbacks", jdbc.queryForList("""
                SELECT id,adapter_code adapterCode,callback_id callbackId,request_id requestId,
                       signed_at signedAt,payload_checksum payloadChecksum,status,replay_count replayCount,
                       processed_at processedAt,created_at createdAt
                FROM integration_callback_inbox WHERE community_id=:communityId
                ORDER BY created_at DESC LIMIT 50
                """, Map.of("communityId", communityId)));
        result.put("attempts", jdbc.queryForList("""
                SELECT id,direction,adapter_code adapterCode,reference_id referenceId,attempt_no attemptNo,
                       outcome,http_status httpStatus,duration_ms durationMs,next_retry_at nextRetryAt,created_at createdAt
                FROM integration_delivery_attempt attempt
                WHERE (attempt.direction='INBOUND' AND EXISTS (
                           SELECT 1 FROM integration_callback_inbox callback
                           WHERE callback.adapter_code=attempt.adapter_code
                             AND callback.callback_id=attempt.reference_id
                             AND callback.community_id=:communityId
                       ))
                   OR (attempt.direction='OUTBOUND' AND EXISTS (
                           SELECT 1 FROM outbox_event event
                           WHERE event.id=attempt.reference_id
                             AND event.aggregate_type='INTEGRATION_SIMULATOR'
                             AND JSON_UNQUOTE(JSON_EXTRACT(event.payload_json,'$.communityId'))=:communityId
                       ))
                   OR (attempt.direction='CONNECTION_TEST'
                       AND JSON_UNQUOTE(JSON_EXTRACT(attempt.detail_json,'$.communityId'))=:communityId)
                ORDER BY attempt.created_at DESC LIMIT 50
                """, Map.of("communityId", communityId)));
        result.put("deadLetters", jdbc.queryForList("""
                SELECT id,direction,adapter_code adapterCode,reference_id referenceId,payload_checksum payloadChecksum,
                       reason,retry_count retryCount,status,replayed_at replayedAt,resolved_at resolvedAt,created_at createdAt
                FROM integration_dead_letter dead
                WHERE EXISTS (
                    SELECT 1 FROM outbox_event event
                    WHERE event.id=dead.reference_id
                      AND event.aggregate_type='INTEGRATION_SIMULATOR'
                      AND JSON_UNQUOTE(JSON_EXTRACT(event.payload_json,'$.communityId'))=:communityId
                )
                ORDER BY dead.created_at DESC LIMIT 50
                """, Map.of("communityId", communityId)));
        result.put("security", Map.of("signedCallbacks", true, "maxSkewSeconds",
                properties.getCallbackMaxSkewSeconds(), "secretConfigured", true, "secretsReadable", false));
        result.put("observability", Map.of("health", "/actuator/health", "readiness",
                "/actuator/health/readiness", "metrics", "/actuator/prometheus", "requestTraceHeader", "X-Request-Id"));
        return result;
    }

    @Transactional
    public Map<String, Object> acceptCallback(String adapterCode, String callbackId, String timestamp,
                                              String signature, String payload, String requestId) {
        if (payload == null || payload.isBlank() || payload.getBytes(StandardCharsets.UTF_8).length > MAX_CALLBACK_BYTES) {
            throw invalid("CALLBACK_PAYLOAD_INVALID", "回调负载为空或超过 1 MB");
        }
        Map<String, Object> policy = policy(adapterCode);
        if (!Boolean.TRUE.equals(policy.get("enabled"))) {
            throw new BusinessException("ADAPTER_DISABLED", "适配器未启用", HttpStatus.SERVICE_UNAVAILABLE);
        }
        signatures.verify(adapterCode, callbackId, timestamp, payload, signature);
        JsonNode body;
        try {
            body = objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw invalid("CALLBACK_PAYLOAD_INVALID", "回调负载不是有效 JSON");
        }
        String communityId = body.path("communityId").asText("");
        if (communityId.isBlank() || !exists("SELECT COUNT(*) FROM community WHERE id=:id", communityId)) {
            throw invalid("CALLBACK_PROJECT_INVALID", "回调缺少有效项目");
        }
        String checksum = sha256(payload);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT * FROM integration_callback_inbox
                WHERE adapter_code=:adapterCode AND callback_id=:callbackId FOR UPDATE
                """, Map.of("adapterCode", adapterCode, "callbackId", callbackId));
        LocalDateTime now = now();
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            if (!checksum.equals(row.get("payload_checksum"))) {
                throw new BusinessException("CALLBACK_REPLAY_CONFLICT",
                        "相同回调编号不能承载不同负载", HttpStatus.CONFLICT);
            }
            jdbc.update("""
                    UPDATE integration_callback_inbox
                    SET replay_count=replay_count+1,updated_at=:now WHERE id=:id
                    """, Map.of("now", now, "id", row.get("id")));
            insertAttempt("INBOUND", adapterCode, callbackId, "REPLAYED", 200, 0,
                    Map.of("callbackInboxId", row.get("id"), "payloadChecksum", checksum), null);
            return callbackResult(String.valueOf(row.get("id")), adapterCode, callbackId, checksum, true);
        }
        String id = UUID.randomUUID().toString();
        LocalDateTime signedAt = LocalDateTime.ofInstant(Instant.parse(timestamp), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO integration_callback_inbox
                    (id,community_id,adapter_code,callback_id,request_id,signed_at,signature_fingerprint,
                     payload_json,payload_checksum,status,replay_count,processed_at,created_at,updated_at)
                VALUES (:id,:communityId,:adapterCode,:callbackId,:requestId,:signedAt,:fingerprint,
                        :payload,:checksum,'PROCESSED',0,:now,:now,:now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", communityId)
                .addValue("adapterCode", adapterCode).addValue("callbackId", callbackId)
                .addValue("requestId", requestId).addValue("signedAt", signedAt)
                .addValue("fingerprint", sha256(signature).substring(0, 16))
                .addValue("payload", payload).addValue("checksum", checksum).addValue("now", now));
        insertAttempt("INBOUND", adapterCode, callbackId, "SUCCEEDED", 200, 0,
                Map.of("callbackInboxId", id, "payloadChecksum", checksum), null);
        return callbackResult(id, adapterCode, callbackId, checksum, false);
    }

    @Transactional
    public Map<String, Object> testAdapter(String communityId, String adapterCode) {
        security.requirePermission("integration:write");
        security.requireProject(communityId);
        Map<String, Object> policy = policy(adapterCode);
        long started = System.nanoTime();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("communityId", communityId);
        String outcome = "SUCCEEDED";
        if (adapterCode.equals(payment.code())) {
            PaymentAdapter.PaymentResult result = payment.confirm("G9-CONNECTION-TEST", BigDecimal.ONE, "QR_SIMULATOR");
            detail.put("status", result.status()); detail.put("simulated", result.simulated());
        } else if (adapterCode.equals(invoice.code())) {
            InvoiceAdapter.InvoiceResult result = invoice.issue("G9-CONNECTION-TEST", BigDecimal.ONE, "G9 模拟测试");
            detail.put("status", result.status()); detail.put("simulated", result.simulated());
        } else if (adapterCode.equals(bank.code())) {
            BankTrustAdapter.TrustResult result = bank.submit("G9-CONNECTION-TEST", 1, BigDecimal.ONE);
            detail.put("status", result.status()); detail.put("simulated", result.simulated());
        } else if (adapterCode.equals(iot.code())) {
            IotAdapter.Reading result = iot.read("G9-CONNECTION-TEST");
            detail.put("meterNo", result.meterNo()); detail.put("simulated", result.simulated());
        } else if (adapterCode.equals(java110.code()) && !java110.enabled()) {
            outcome = "DISABLED";
            detail.put("status", "DISABLED"); detail.put("simulated", false);
        } else {
            throw invalid("ADAPTER_NOT_SUPPORTED", "未知适配器");
        }
        long duration = (System.nanoTime() - started) / 1_000_000;
        String reference = UUID.randomUUID().toString();
        insertAttempt("CONNECTION_TEST", adapterCode, reference, outcome, null, duration, detail, null);
        jdbc.update("UPDATE integration_adapter_policy SET last_checked_at=:now,updated_at=:now WHERE adapter_code=:code",
                Map.of("now", now(), "code", adapterCode));
        audit.success(communityId, "integration:test", "integration-adapter", adapterCode,
                Map.of("mode", policy.get("mode"), "outcome", outcome, "durationMs", duration));
        Map<String, Object> response = new LinkedHashMap<>(detail);
        response.put("adapterCode", adapterCode); response.put("mode", policy.get("mode"));
        response.put("outcome", outcome); response.put("durationMs", duration); response.put("productionReady", false);
        return response;
    }

    @Transactional
    public Map<String, Object> createSimulatedOutbox(IntegrationModels.SimulatedOutboxRequest request) {
        security.requirePermission("integration:write");
        security.requireRole("PLATFORM_ADMIN");
        security.requireProject(request.communityId());
        String id = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>(request.payload() == null ? Map.of() : request.payload());
        payload.put("simulated", true);
        payload.put("communityId", request.communityId());
        String json = json(payload);
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO outbox_event
                    (id,aggregate_type,aggregate_id,event_type,payload_json,request_id,payload_checksum,
                     status,available_at,retry_count,created_at,updated_at)
                VALUES (:id,'INTEGRATION_SIMULATOR',:id,:eventType,:payload,NULL,:checksum,
                        'PENDING',:now,0,:now,:now)
                """, Map.of("id", id, "eventType", request.eventType(), "payload", json,
                "checksum", sha256(json), "now", now));
        audit.success(request.communityId(), "integration:simulate-outbox", "outbox-event", id,
                Map.of("eventType", request.eventType(), "simulated", true));
        return outbox(id);
    }

    @Transactional
    public Map<String, Object> simulateDelivery(String id, IntegrationModels.SimulatedDeliveryRequest request) {
        security.requirePermission("integration:write");
        security.requireRole("PLATFORM_ADMIN");
        return deliver(id, request.adapterCode(), request.outcome());
    }

    @Transactional
    public Map<String, Object> drainSimulated(String communityId) {
        security.requirePermission("integration:write");
        security.requireRole("PLATFORM_ADMIN");
        security.requireProject(communityId);
        List<String> ids = jdbc.queryForList("""
                SELECT id FROM outbox_event
                WHERE aggregate_type='INTEGRATION_SIMULATOR'
                  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.communityId'))=:communityId
                  AND status IN ('PENDING','RETRY') AND available_at<=:now ORDER BY created_at LIMIT 100
                FOR UPDATE SKIP LOCKED
                """, Map.of("now", now(), "communityId", communityId), String.class);
        for (String id : ids) deliver(id, payment.code(), "SUCCEEDED");
        audit.success(communityId, "integration:drain-simulated", "outbox-event", communityId,
                Map.of("processed", ids.size(), "simulated", true));
        return Map.of("processed", ids.size(), "adapterCode", payment.code(), "simulated", true);
    }

    @Transactional
    public Map<String, Object> replayDeadLetter(String id) {
        security.requirePermission("integration:write");
        security.requireRole("PLATFORM_ADMIN");
        Map<String, Object> dead = required("""
                SELECT dead.* FROM integration_dead_letter dead
                JOIN outbox_event event ON event.id=dead.reference_id
                WHERE dead.id=:id AND event.aggregate_type='INTEGRATION_SIMULATOR' FOR UPDATE
                """,
                Map.of("id", id), "DEAD_LETTER_NOT_FOUND", "死信不存在");
        if ("RESOLVED".equals(dead.get("status"))) return Map.of("id", id, "replayed", true, "status", "RESOLVED");
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE integration_dead_letter SET status='REPLAYED',replayed_at=:now,updated_at=:now WHERE id=:id
                """, Map.of("now", now, "id", id));
        jdbc.update("""
                UPDATE outbox_event SET status='PENDING',retry_count=0,last_error=NULL,available_at=:now,
                       locked_at=NULL,updated_at=:now WHERE id=:referenceId
                """, Map.of("now", now, "referenceId", dead.get("reference_id")));
        return Map.of("id", id, "referenceId", dead.get("reference_id"), "status", "REPLAYED", "replayed", true);
    }

    private Map<String, Object> deliver(String id, String adapterCode, String outcome) {
        Map<String, Object> event = required("""
                SELECT * FROM outbox_event
                WHERE id=:id AND aggregate_type='INTEGRATION_SIMULATOR'
                  AND JSON_EXTRACT(payload_json,'$.simulated')=TRUE FOR UPDATE
                """,
                Map.of("id", id), "OUTBOX_EVENT_NOT_FOUND", "Outbox 事件不存在");
        Map<String, Object> policy = policy(adapterCode);
        if ("PUBLISHED".equals(event.get("status"))) return outbox(id);
        LocalDateTime now = now();
        if ("SUCCEEDED".equals(outcome)) {
            jdbc.update("""
                    UPDATE outbox_event SET status='PUBLISHED',published_at=:now,last_error=NULL,
                           locked_at=NULL,updated_at=:now WHERE id=:id
                    """, Map.of("now", now, "id", id));
            insertAttempt("OUTBOUND", adapterCode, id, "SUCCEEDED", 200, 0,
                    Map.of("eventType", event.get("event_type"), "simulated", true), null);
            jdbc.update("""
                    UPDATE integration_dead_letter SET status='RESOLVED',resolved_at=:now,updated_at=:now
                    WHERE direction='OUTBOUND' AND adapter_code=:adapterCode AND reference_id=:id
                    """, Map.of("now", now, "adapterCode", adapterCode, "id", id));
        } else {
            int retries = ((Number) event.get("retry_count")).intValue() + 1;
            int maxAttempts = ((Number) policy.get("max_attempts")).intValue();
            int retryBase = ((Number) policy.get("retry_base_seconds")).intValue();
            boolean dead = retries >= maxAttempts;
            LocalDateTime next = dead ? null : now.plusSeconds((long) retryBase * retries);
            MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("retries", retries)
                    .addValue("status", dead ? "DEAD_LETTER" : "RETRY").addValue("error", "G9_SIMULATED_RETRYABLE_FAILURE")
                    .addValue("next", dead ? now : next).addValue("now", now);
            jdbc.update("""
                    UPDATE outbox_event SET status=:status,retry_count=:retries,last_error=:error,
                           available_at=:next,locked_at=NULL,updated_at=:now WHERE id=:id
                    """, params);
            insertAttempt("OUTBOUND", adapterCode, id, "RETRYABLE_FAILURE", 503, 0,
                    Map.of("eventType", event.get("event_type"), "simulated", true, "retryCount", retries), next);
            if (dead) {
                String deadId = UUID.randomUUID().toString();
                jdbc.update("""
                        INSERT INTO integration_dead_letter
                            (id,direction,adapter_code,reference_id,payload_checksum,reason,retry_count,status,created_at,updated_at)
                        VALUES (:id,'OUTBOUND',:adapterCode,:referenceId,:checksum,:reason,:retries,'OPEN',:now,:now)
                        ON DUPLICATE KEY UPDATE reason=VALUES(reason),retry_count=VALUES(retry_count),status='OPEN',
                                                replayed_at=NULL,resolved_at=NULL,updated_at=VALUES(updated_at)
                        """, new MapSqlParameterSource("id", deadId).addValue("adapterCode", adapterCode)
                        .addValue("referenceId", id).addValue("checksum", event.get("payload_checksum"))
                        .addValue("reason", "模拟投递达到最大重试次数").addValue("retries", retries).addValue("now", now));
            }
        }
        return outbox(id);
    }

    private Map<String, Object> callbackResult(String id, String adapterCode, String callbackId,
                                               String checksum, boolean replayed) {
        return Map.of("id", id, "adapterCode", adapterCode, "callbackId", callbackId,
                "payloadChecksum", checksum, "status", "PROCESSED", "replayed", replayed, "simulated", true);
    }

    private Map<String, Object> outbox(String id) {
        return required("""
                SELECT id,aggregate_type aggregateType,aggregate_id aggregateId,event_type eventType,
                       payload_checksum payloadChecksum,status,retry_count retryCount,available_at availableAt,
                       published_at publishedAt,last_error lastError,created_at createdAt,updated_at updatedAt
                FROM outbox_event WHERE id=:id
                """, Map.of("id", id), "OUTBOX_EVENT_NOT_FOUND", "Outbox 事件不存在");
    }

    private Map<String, Object> policy(String adapterCode) {
        return required("SELECT * FROM integration_adapter_policy WHERE adapter_code=:code",
                Map.of("code", adapterCode), "ADAPTER_NOT_SUPPORTED", "未知适配器");
    }

    private int nextAttempt(String direction, String adapterCode, String referenceId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_no),0)+1 FROM integration_delivery_attempt
                WHERE direction=:direction AND adapter_code=:adapterCode AND reference_id=:referenceId
                """, Map.of("direction", direction, "adapterCode", adapterCode, "referenceId", referenceId), Integer.class);
        return value == null ? 1 : value;
    }

    private void insertAttempt(String direction, String adapterCode, String referenceId, String outcome,
                               Integer httpStatus, long durationMs, Object detail, LocalDateTime nextRetryAt) {
        String detailJson = json(detail);
        jdbc.update("""
                INSERT INTO integration_delivery_attempt
                    (id,direction,adapter_code,reference_id,attempt_no,outcome,http_status,duration_ms,
                     detail_json,detail_checksum,next_retry_at,created_at)
                VALUES (:id,:direction,:adapterCode,:referenceId,:attemptNo,:outcome,:httpStatus,:durationMs,
                        :detail,:checksum,:nextRetryAt,:now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("direction", direction)
                .addValue("adapterCode", adapterCode).addValue("referenceId", referenceId)
                .addValue("attemptNo", nextAttempt(direction, adapterCode, referenceId)).addValue("outcome", outcome)
                .addValue("httpStatus", httpStatus).addValue("durationMs", durationMs).addValue("detail", detailJson)
                .addValue("checksum", sha256(detailJson)).addValue("nextRetryAt", nextRetryAt).addValue("now", now()));
    }

    private boolean exists(String sql, String id) {
        Integer count = jdbc.queryForObject(sql, Map.of("id", id), Integer.class);
        return count != null && count > 0;
    }

    private Map<String, Object> required(String sql, Map<String, ?> params, String code, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) throw new BusinessException(code, message, HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw invalid("JSON_SERIALIZATION_FAILED", "无法序列化集成证据");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot compute SHA-256", exception);
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    private BusinessException invalid(String code, String message) {
        return new BusinessException(code, message, HttpStatus.BAD_REQUEST);
    }
}
