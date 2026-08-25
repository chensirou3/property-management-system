package com.propertyops.pms.iam;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.propertyops.pms.security.AuthPrincipal;

@Repository
public class AuthRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AuthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        String sql = """
                SELECT id, username, password_hash, display_name, enabled, password_change_required, session_version
                  FROM sys_user
                 WHERE username = :username
                """;
        return jdbc.query(sql, new MapSqlParameterSource("username", username), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new UserAccount(
                    rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
                    rs.getString("display_name"), rs.getBoolean("enabled"),
                    rs.getBoolean("password_change_required"), rs.getLong("session_version")));
        });
    }

    public Optional<UserAccount> findById(String id) {
        String sql = """
                SELECT id, username, password_hash, display_name, enabled, password_change_required, session_version
                  FROM sys_user WHERE id=:id
                """;
        return jdbc.query(sql, new MapSqlParameterSource("id", id), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new UserAccount(
                    rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
                    rs.getString("display_name"), rs.getBoolean("enabled"),
                    rs.getBoolean("password_change_required"), rs.getLong("session_version")));
        });
    }

    public AuthPrincipal loadPrincipal(UserAccount account) {
        var params = new MapSqlParameterSource("userId", account.id());
        Set<String> roles = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT r.code FROM sys_role r
                JOIN sys_user_role ur ON ur.role_id = r.id
                WHERE ur.user_id = :userId AND r.enabled = TRUE ORDER BY r.code
                """, params, String.class));
        Set<String> permissions = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT p.code FROM sys_permission p
                JOIN sys_role_permission rp ON rp.permission_id = p.id
                JOIN sys_user_role ur ON ur.role_id = rp.role_id
                JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = :userId AND r.enabled = TRUE ORDER BY p.code
                """, params, String.class));
        Set<String> projects = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT s.community_id FROM sys_user_project_scope s
                JOIN community c ON c.id = s.community_id
                WHERE s.user_id = :userId AND c.status = 'ACTIVE' ORDER BY s.community_id
                """, params, String.class));
        return new AuthPrincipal(account.id(), account.username(), account.displayName(), roles, permissions, projects);
    }

    public void markLogin(String userId) {
        jdbc.update("UPDATE sys_user SET last_login_at = CURRENT_TIMESTAMP(3) WHERE id = :id",
                new MapSqlParameterSource("id", userId));
    }

    public Optional<SessionState> findSessionState(String userId) {
        return jdbc.query("""
                SELECT enabled, password_change_required, session_version
                  FROM sys_user WHERE id=:id
                """, new MapSqlParameterSource("id", userId), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new SessionState(rs.getBoolean("enabled"),
                    rs.getBoolean("password_change_required"), rs.getLong("session_version")));
        });
    }

    public int changePassword(String userId, long expectedSessionVersion, String passwordHash) {
        return jdbc.update("""
                UPDATE sys_user
                   SET password_hash=:passwordHash, password_change_required=FALSE,
                       password_changed_at=CURRENT_TIMESTAMP(3), session_version=session_version+1,
                       version=version+1, updated_at=CURRENT_TIMESTAMP(3)
                 WHERE id=:id AND session_version=:sessionVersion AND enabled=TRUE
                """, new MapSqlParameterSource().addValue("passwordHash", passwordHash)
                .addValue("id", userId).addValue("sessionVersion", expectedSessionVersion));
    }

    public long revokeSessions(String userId) {
        jdbc.update("""
                UPDATE sys_user SET session_version=session_version+1, version=version+1,
                       updated_at=CURRENT_TIMESTAMP(3) WHERE id=:id
                """, new MapSqlParameterSource("id", userId));
        return jdbc.queryForObject("SELECT session_version FROM sys_user WHERE id=:id",
                new MapSqlParameterSource("id", userId), Long.class);
    }

    public record UserAccount(String id, String username, String passwordHash, String displayName, boolean enabled,
                              boolean passwordChangeRequired, long sessionVersion) {}
    public record SessionState(boolean enabled, boolean passwordChangeRequired, long sessionVersion) {}
}
