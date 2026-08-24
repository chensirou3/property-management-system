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
                SELECT id, username, password_hash, display_name, enabled
                  FROM sys_user
                 WHERE username = :username
                """;
        return jdbc.query(sql, new MapSqlParameterSource("username", username), rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(new UserAccount(
                    rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
                    rs.getString("display_name"), rs.getBoolean("enabled")));
        });
    }

    public AuthPrincipal loadPrincipal(UserAccount account) {
        var params = new MapSqlParameterSource("userId", account.id());
        Set<String> roles = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT r.code FROM sys_role r
                JOIN sys_user_role ur ON ur.role_id = r.id
                WHERE ur.user_id = :userId ORDER BY r.code
                """, params, String.class));
        Set<String> permissions = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT p.code FROM sys_permission p
                JOIN sys_role_permission rp ON rp.permission_id = p.id
                JOIN sys_user_role ur ON ur.role_id = rp.role_id
                WHERE ur.user_id = :userId ORDER BY p.code
                """, params, String.class));
        Set<String> projects = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT community_id FROM sys_user_project_scope
                WHERE user_id = :userId ORDER BY community_id
                """, params, String.class));
        return new AuthPrincipal(account.id(), account.username(), account.displayName(), roles, permissions, projects);
    }

    public record UserAccount(String id, String username, String passwordHash, String displayName, boolean enabled) {}
}

