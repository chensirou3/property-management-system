package com.propertyops.pms.common.audit;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
        var parameters = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString())
                .addValue("actor", principal.userId())
                .addValue("community", communityId)
                .addValue("action", action)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("requestId", request.getHeader("X-Request-Id"))
                .addValue("detail", json(detail))
                .addValue("occurredAt", LocalDateTime.now(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO audit_event
                    (id, actor_user_id, community_id, action_code, resource_type, resource_id,
                     request_id, result_status, detail_json, occurred_at)
                VALUES (:id, :actor, :community, :action, :resourceType, :resourceId,
                        :requestId, 'SUCCESS', :detail, :occurredAt)
                """, parameters);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"serializationError\":true}";
        }
    }
}
