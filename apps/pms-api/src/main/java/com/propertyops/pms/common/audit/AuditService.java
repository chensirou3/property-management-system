package com.propertyops.pms.common.audit;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.propertyops.pms.security.SecurityContextService;

@Service
public class AuditService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest request;

    public AuditService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                        ObjectMapper objectMapper, HttpServletRequest request) {
        this.jdbc = jdbc;
        this.security = security;
        this.objectMapper = objectMapper;
        this.request = request;
    }

    public void success(String communityId, String action, String resourceType, String resourceId, Object detail) {
        var principal = security.requirePrincipal();
        jdbc.update("""
                INSERT INTO audit_event
                    (id, actor_user_id, community_id, action_code, resource_type, resource_id,
                     request_id, result_status, detail_json, occurred_at)
                VALUES (:id, :actor, :community, :action, :resourceType, :resourceId,
                        :requestId, 'SUCCESS', :detail, :occurredAt)
                """, Map.ofEntries(
                Map.entry("id", UUID.randomUUID().toString()),
                Map.entry("actor", principal.userId()),
                Map.entry("community", communityId == null ? "" : communityId),
                Map.entry("action", action),
                Map.entry("resourceType", resourceType),
                Map.entry("resourceId", resourceId == null ? "" : resourceId),
                Map.entry("requestId", request.getHeader("X-Request-Id") == null ? "" : request.getHeader("X-Request-Id")),
                Map.entry("detail", json(detail)),
                Map.entry("occurredAt", LocalDateTime.now(ZoneOffset.UTC))));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"serializationError\":true}";
        }
    }
}
