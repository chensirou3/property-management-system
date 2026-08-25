package com.propertyops.pms.visitor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class VisitorService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final VisitorAccessAdapter adapter;

    public VisitorService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                          AuditService audit, VisitorAccessAdapter adapter) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.adapter = adapter;
    }

    public Map<String, Object> records(String communityId, LocalDate from, LocalDate to,
                                      String keyword, String status, int page, int size) {
        read(communityId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        LocalDateTime fromAt = (from == null ? LocalDate.of(2020, 1, 1) : from).atStartOfDay();
        LocalDateTime toAt = (to == null ? LocalDate.of(2100, 1, 1) : to.plusDays(1)).atStartOfDay();
        String term = keyword == null ? "" : keyword.trim();
        String state = status == null ? "" : status.trim();
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId)
                .addValue("fromAt", fromAt).addValue("toAt", toAt).addValue("keyword", "%" + term + "%")
                .addValue("status", state).addValue("offset", (safePage - 1) * safeSize).addValue("size", safeSize);
        String where = """
                FROM visitor_record
                WHERE community_id=:communityId AND scheduled_at>=:fromAt AND scheduled_at<:toAt
                  AND (:status='' OR visit_status=:status)
                  AND (:keyword='%%' OR visit_no LIKE :keyword OR visitor_name_masked LIKE :keyword
                       OR visitor_mobile_masked LIKE :keyword OR host_name_masked LIKE :keyword OR asset_name LIKE :keyword)
                """;
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id,community_id,visit_no,visitor_name_masked,visitor_mobile_masked,
                       host_name_masked,asset_name,scheduled_at,check_in_at,check_out_at,
                       visit_status,source_mode,production_connected,adapter_evidence,version,
                       created_at,updated_at
                """ + where + " ORDER BY scheduled_at DESC,visit_no DESC LIMIT :offset,:size", params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + where, params, Long.class);
        return Map.of("items", items, "page", safePage, "size", safeSize,
                "total", total == null ? 0 : total, "adapterCode", adapter.code(),
                "simulated", true, "productionConnected", false, "sensitiveFieldsStoredMasked", true);
    }

    @Transactional
    public Map<String, Object> register(VisitorModels.RegisterRequest request) {
        write(request.communityId());
        requireMasked(request.visitorNameMasked(), "访客姓名");
        requireMasked(request.hostNameMasked(), "受访人姓名");
        requireSimulatorReady();
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String visitNo = "SYN-VISIT-" + id.substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO visitor_record
                    (id,community_id,visit_no,visitor_name_masked,visitor_mobile_masked,host_name_masked,
                     asset_name,scheduled_at,check_in_at,check_out_at,visit_status,source_mode,
                     production_connected,adapter_evidence,version,created_at,updated_at)
                VALUES (:id,:communityId,:visitNo,:visitorName,:visitorMobile,:hostName,
                        :assetName,:scheduledAt,NULL,NULL,'REGISTERED',:sourceMode,
                        FALSE,'registered:simulated-only',0,:now,:now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("visitNo", visitNo).addValue("visitorName", request.visitorNameMasked().trim())
                .addValue("visitorMobile", request.visitorMobileMasked()).addValue("hostName", request.hostNameMasked().trim())
                .addValue("assetName", request.assetName().trim()).addValue("scheduledAt", request.scheduledAt())
                .addValue("sourceMode", adapter.code()).addValue("now", now));
        audit.success(request.communityId(), "visitor:register-simulated", "visitor-record", id,
                Map.of("visitNo", visitNo, "adapterCode", adapter.code(), "productionConnected", false));
        return response(required(id, request.communityId()), false);
    }

    @Transactional
    public Map<String, Object> checkIn(String id, VisitorModels.TransitionRequest request) {
        return transition(id, request, "REGISTERED", "CHECKED_IN", "CHECK_IN");
    }

    @Transactional
    public Map<String, Object> checkOut(String id, VisitorModels.TransitionRequest request) {
        return transition(id, request, "CHECKED_IN", "CHECKED_OUT", "CHECK_OUT");
    }

    private Map<String, Object> transition(String id, VisitorModels.TransitionRequest request,
                                           String fromStatus, String toStatus, String action) {
        write(request.communityId());
        requireSimulatorReady();
        Map<String, Object> record = requiredForUpdate(id, request.communityId());
        if (toStatus.equals(record.get("visit_status"))) return response(record, true);
        if (!fromStatus.equals(record.get("visit_status"))) {
            throw new BusinessException("VISITOR_STATE_CONFLICT", "当前访客状态不能执行该操作", HttpStatus.CONFLICT);
        }
        if (((Number) record.get("version")).longValue() != request.expectedVersion()) {
            throw new BusinessException("OPTIMISTIC_LOCK_CONFLICT", "访客记录已被其他操作更新", HttpStatus.CONFLICT);
        }
        VisitorAccessAdapter.TransitionEvidence evidence = adapter.transition(String.valueOf(record.get("visit_no")), action);
        if (!evidence.simulated() || evidence.productionConnected()) {
            throw new BusinessException("VISITOR_ADAPTER_FAIL_CLOSED", "访客通道未处于允许的模拟模式", HttpStatus.SERVICE_UNAVAILABLE);
        }
        String timestampColumn = "CHECK_IN".equals(action) ? "check_in_at" : "check_out_at";
        int changed = jdbc.update("UPDATE visitor_record SET visit_status=:status," + timestampColumn + "=:occurredAt," +
                        "adapter_evidence=:evidence,version=version+1,updated_at=:occurredAt " +
                        "WHERE id=:id AND community_id=:communityId AND visit_status=:fromStatus AND version=:version",
                Map.of("status", toStatus, "occurredAt", evidence.occurredAt(), "evidence", evidence.evidence(),
                        "id", id, "communityId", request.communityId(), "fromStatus", fromStatus,
                        "version", request.expectedVersion()));
        if (changed != 1) throw new BusinessException("OPTIMISTIC_LOCK_CONFLICT", "访客记录已被其他操作更新", HttpStatus.CONFLICT);
        audit.success(request.communityId(), "visitor:" + action.toLowerCase().replace('_', '-'), "visitor-record", id,
                Map.of("adapterCode", adapter.code(), "evidence", evidence.evidence(), "productionConnected", false));
        return response(required(id, request.communityId()), false);
    }

    private void requireSimulatorReady() {
        List<Map<String, Object>> policies = jdbc.queryForList("""
                SELECT mode,enabled,production_ready FROM integration_adapter_policy WHERE adapter_code=:code
                """, Map.of("code", adapter.code()));
        if (policies.size() != 1 || !"SIMULATOR".equals(policies.get(0).get("mode"))
                || !Boolean.TRUE.equals(policies.get(0).get("enabled"))
                || Boolean.TRUE.equals(policies.get(0).get("production_ready"))
                || adapter.productionConnected()) {
            throw new BusinessException("VISITOR_ADAPTER_FAIL_CLOSED", "访客通道未处于允许的模拟模式", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void requireMasked(String value, String field) {
        if (!value.contains("*") && !value.startsWith("合成")) {
            throw new BusinessException("SENSITIVE_VALUE_NOT_MASKED", field + "必须使用合成或脱敏值", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private Map<String, Object> response(Map<String, Object> record, boolean replayed) {
        Map<String, Object> result = new LinkedHashMap<>(record);
        result.put("replayed", replayed);
        result.put("simulated", true);
        result.put("productionConnected", false);
        return result;
    }

    private Map<String, Object> required(String id, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM visitor_record WHERE id=:id AND community_id=:communityId",
                Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw new BusinessException("VISITOR_RECORD_NOT_FOUND", "访客记录不存在", HttpStatus.NOT_FOUND);
        return new LinkedHashMap<>(rows.get(0));
    }

    private Map<String, Object> requiredForUpdate(String id, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM visitor_record WHERE id=:id AND community_id=:communityId FOR UPDATE",
                Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw new BusinessException("VISITOR_RECORD_NOT_FOUND", "访客记录不存在", HttpStatus.NOT_FOUND);
        return new LinkedHashMap<>(rows.get(0));
    }

    private void read(String communityId) {
        security.requirePermission("visitor:read");
        security.requireProject(communityId);
    }

    private void write(String communityId) {
        security.requirePermission("visitor:write");
        security.requireProject(communityId);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
