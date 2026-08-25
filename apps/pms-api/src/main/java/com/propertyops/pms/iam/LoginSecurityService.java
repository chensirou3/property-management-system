package com.propertyops.pms.iam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.api.RequestIdFilter;
import com.propertyops.pms.security.SecurityProperties;

@Service
public class LoginSecurityService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest request;

    public LoginSecurityService(NamedParameterJdbcTemplate jdbc, SecurityProperties properties,
                                ObjectMapper objectMapper, HttpServletRequest request) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.request = request;
    }

    public void assertAllowed(String normalizedUsername) {
        LocalDateTime now = now();
        LocalDateTime lockedUntil = guards(normalizedUsername).stream()
                .map(key -> jdbc.query("""
                        SELECT locked_until FROM auth_login_guard WHERE guard_key=:key
                        """, Map.of("key", key.key()), rs -> rs.next() && rs.getTimestamp(1) != null
                        ? rs.getTimestamp(1).toLocalDateTime() : null))
                .filter(value -> value != null && value.isAfter(now))
                .max(LocalDateTime::compareTo)
                .orElse(null);
        if (lockedUntil != null) {
            audit(null, normalizedUsername, "AUTH_LOGIN_RATE_LIMITED", "DENIED", "RATE_LIMITED", lockedUntil);
            throw rateLimited();
        }
    }

    @Transactional
    public boolean recordFailure(String normalizedUsername, String userId) {
        LocalDateTime now = now();
        LocalDateTime windowExpiredBefore = now.minusMinutes(properties.getLoginWindowMinutes());
        LocalDateTime lockUntil = now.plusMinutes(properties.getLoginLockMinutes());
        boolean locked = false;
        for (GuardKey guard : guards(normalizedUsername)) {
            jdbc.update("""
                    INSERT IGNORE INTO auth_login_guard
                        (guard_key, guard_type, failure_count, window_started_at, locked_until, updated_at)
                    VALUES (:key, :type, 0, :now, NULL, :now)
                    """, new MapSqlParameterSource().addValue("key", guard.key())
                    .addValue("type", guard.type()).addValue("now", now));
            GuardState state = jdbc.queryForObject("""
                    SELECT failure_count, window_started_at, locked_until
                      FROM auth_login_guard WHERE guard_key=:key FOR UPDATE
                    """, Map.of("key", guard.key()), (rs, row) -> new GuardState(
                    rs.getInt("failure_count"), rs.getTimestamp("window_started_at").toLocalDateTime(),
                    rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toLocalDateTime()));
            boolean reset = state == null || state.windowStartedAt().isBefore(windowExpiredBefore)
                    || (state.lockedUntil() != null && !state.lockedUntil().isAfter(now));
            int nextFailures = reset ? 1 : state.failureCount() + 1;
            LocalDateTime nextWindow = reset ? now : state.windowStartedAt();
            LocalDateTime nextLock = nextFailures >= properties.getLoginMaxFailures() ? lockUntil : null;
            jdbc.update("""
                    UPDATE auth_login_guard
                       SET failure_count=:failures, window_started_at=:windowStarted,
                           locked_until=:lockedUntil, updated_at=:now
                     WHERE guard_key=:key
                    """, new MapSqlParameterSource().addValue("failures", nextFailures)
                    .addValue("windowStarted", nextWindow).addValue("lockedUntil", nextLock)
                    .addValue("now", now).addValue("key", guard.key()));
            locked = locked || nextLock != null;
        }
        audit(userId, normalizedUsername, "AUTH_LOGIN_FAILURE", locked ? "DENIED" : "FAILURE",
                "INVALID_CREDENTIALS", locked ? lockUntil : null);
        return locked;
    }

    @Transactional
    public void recordSuccess(String normalizedUsername, String userId) {
        List<String> keys = guards(normalizedUsername).stream().map(GuardKey::key).toList();
        jdbc.update("DELETE FROM auth_login_guard WHERE guard_key IN (:keys)", Map.of("keys", keys));
        audit(userId, normalizedUsername, "AUTH_LOGIN_SUCCESS", "SUCCESS", "AUTHENTICATED", null);
    }

    public BusinessException rateLimited() {
        return new BusinessException("TOO_MANY_LOGIN_ATTEMPTS", "登录尝试次数过多，请稍后再试",
                HttpStatus.TOO_MANY_REQUESTS);
    }

    private List<GuardKey> guards(String normalizedUsername) {
        return List.of(new GuardKey("USER", "U:" + hash(normalizedUsername)),
                new GuardKey("CLIENT", "I:" + hash(clientAddress())));
    }

    private void audit(String userId, String normalizedUsername, String action, String result,
                       String reason, LocalDateTime lockedUntil) {
        String detail;
        try {
            detail = objectMapper.writeValueAsString(Map.of(
                    "usernameHash", hash(normalizedUsername),
                    "clientHash", hash(clientAddress()),
                    "reason", reason,
                    "lockedUntil", lockedUntil == null ? "" : lockedUntil.toString()));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize login audit detail", error);
        }
        Object requestIdValue = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        var params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID().toString()).addValue("actor", userId)
                .addValue("action", action).addValue("resourceId", userId)
                .addValue("requestId", requestIdValue == null ? null : requestIdValue.toString())
                .addValue("result", result).addValue("detail", detail).addValue("now", now());
        jdbc.update("""
                INSERT INTO audit_event
                    (id, actor_user_id, community_id, action_code, resource_type, resource_id,
                     request_id, result_status, detail_json, occurred_at)
                VALUES (:id, :actor, NULL, :action, 'AUTH_ACCOUNT', :resourceId,
                        :requestId, :result, :detail, :now)
                """, params);
    }

    private String clientAddress() {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record GuardKey(String type, String key) {}
    private record GuardState(int failureCount, LocalDateTime windowStartedAt, LocalDateTime lockedUntil) {}
}
