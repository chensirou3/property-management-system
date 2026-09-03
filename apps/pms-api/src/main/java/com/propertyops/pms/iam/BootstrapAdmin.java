package com.propertyops.pms.iam;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private static final String ADMIN_ROLE_ID = "10000000-0000-0000-0000-000000000001";

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final String username;
    private final String password;

    public BootstrapAdmin(NamedParameterJdbcTemplate jdbc,
                          PasswordEncoder passwordEncoder,
                          PasswordPolicy passwordPolicy,
                          @Value("${pms.bootstrap.admin-username:}") String username,
                          @Value("${pms.bootstrap.admin-password:}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            log.info("Bootstrap administrator is disabled; use the one-time web setup on a fresh database");
            return;
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("PMS_BOOTSTRAP_ADMIN_USERNAME is required when bootstrap password is set");
        }
        var parameters = Map.of("username", username);
        var ids = jdbc.queryForList("SELECT id FROM sys_user WHERE username = :username", parameters, String.class);
        String userId;
        if (ids.isEmpty()) {
            var projectIds = jdbc.queryForList(
                    "SELECT id FROM community WHERE status='ACTIVE' ORDER BY created_at, id",
                    Map.of(), String.class);
            if (projectIds.isEmpty()) {
                log.info("Bootstrap administrator deferred because no project exists; use the one-time web setup");
                return;
            }
            passwordPolicy.validate(username, password);
            userId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbc.update("""
                    INSERT INTO sys_user (id, username, password_hash, display_name, enabled, version, created_at, updated_at)
                    VALUES (:id, :username, :passwordHash, :displayName, TRUE, 0, :now, :now)
                    """, Map.of(
                    "id", userId,
                    "username", username,
                    "passwordHash", passwordEncoder.encode(password),
                    "displayName", "本地系统管理员",
                    "now", now));
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (:userId, :roleId)",
                    Map.of("userId", userId, "roleId", ADMIN_ROLE_ID));
            log.info("Created local bootstrap administrator username={}; password was supplied by environment", username);
        } else {
            userId = ids.get(0);
        }
        jdbc.update("""
                INSERT IGNORE INTO sys_user_project_scope (user_id, community_id, data_scope)
                SELECT :userId, id, 'PROJECT' FROM community
                """, Map.of("userId", userId));
        jdbc.update("""
                UPDATE system_setup
                   SET initialized=TRUE,
                       enterprise_id=COALESCE(enterprise_id, (SELECT id FROM enterprise ORDER BY created_at, id LIMIT 1)),
                       community_id=COALESCE(community_id, (SELECT id FROM community WHERE status='ACTIVE' ORDER BY created_at, id LIMIT 1)),
                       initialized_by=COALESCE(initialized_by, :userId),
                       initialized_at=COALESCE(initialized_at, CURRENT_TIMESTAMP(3)),
                       version=CASE WHEN initialized=FALSE THEN version+1 ELSE version END,
                       updated_at=CURRENT_TIMESTAMP(3), initialized=TRUE
                 WHERE singleton_id=1
                """, Map.of("userId", userId));
    }
}
