package com.propertyops.pms.setup;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.iam.PasswordPolicy;

@Service
public class SetupService {
    static final String ADMIN_ROLE_ID = "10000000-0000-0000-0000-000000000001";

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public SetupService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                        PasswordPolicy passwordPolicy) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    public SetupStatus status() {
        return jdbc.queryForObject("""
                SELECT (s.initialized OR EXISTS(SELECT 1 FROM sys_user)) initialized,
                       s.deployment_mode,
                       e.name company_name,
                       c.name project_name
                  FROM system_setup s
                  LEFT JOIN enterprise e ON e.id=s.enterprise_id
                  LEFT JOIN community c ON c.id=s.community_id
                 WHERE s.singleton_id=1
                """, Map.of(), (rs, rowNum) -> new SetupStatus(
                rs.getBoolean("initialized"),
                rs.getString("deployment_mode"),
                rs.getString("company_name"),
                rs.getString("project_name")));
    }

    @Transactional
    public SetupResult initialize(SetupController.InitializeRequest request) {
        Boolean initialized = jdbc.queryForObject(
                "SELECT initialized FROM system_setup WHERE singleton_id=1 FOR UPDATE",
                Map.of(), Boolean.class);
        Long userCount = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Map.of(), Long.class);
        if (Boolean.TRUE.equals(initialized) || userCount == null || userCount > 0) {
            throw new BusinessException("SETUP_ALREADY_COMPLETED", "系统已经完成初始化，不能再次创建首个管理员",
                    HttpStatus.CONFLICT);
        }

        String companyName = normalizeName(request.companyName(), "物业企业名称");
        String projectName = normalizeName(request.projectName(), "项目名称");
        String username = request.adminUsername().trim();
        String displayName = normalizeName(request.adminDisplayName(), "管理员姓名");
        passwordPolicy.validate(username, request.adminPassword());

        String enterpriseId = requiredId("SELECT id FROM enterprise ORDER BY created_at, id LIMIT 1 FOR UPDATE");
        String communityId = jdbc.query("""
                SELECT id FROM community
                 WHERE enterprise_id=:enterpriseId
                 ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """, Map.of("enterpriseId", enterpriseId), rs -> {
            if (!rs.next()) {
                throw new BusinessException("SETUP_BASELINE_MISSING", "初始化基线不存在，请重新部署干净数据库",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return rs.getString(1);
        });
        String userId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("enterpriseId", enterpriseId)
                .addValue("communityId", communityId)
                .addValue("companyName", companyName)
                .addValue("projectName", projectName)
                .addValue("username", username)
                .addValue("displayName", displayName)
                .addValue("passwordHash", passwordEncoder.encode(request.adminPassword()))
                .addValue("userId", userId)
                .addValue("roleId", ADMIN_ROLE_ID)
                .addValue("now", now);

        jdbc.update("""
                UPDATE enterprise
                   SET name=:companyName, status='ACTIVE', version=version+1, updated_at=:now
                 WHERE id=:enterpriseId
                """, parameters);
        jdbc.update("""
                UPDATE community
                   SET name=CASE WHEN id=:communityId THEN :projectName ELSE name END,
                       source_system=CASE WHEN id=:communityId THEN 'LOCAL_SETUP' ELSE source_system END,
                       source_id=CASE WHEN id=:communityId THEN 'LOCAL-COMMUNITY-001' ELSE source_id END,
                       status=CASE WHEN id=:communityId THEN 'ACTIVE' ELSE 'INACTIVE' END,
                       version=version+1, updated_at=:now
                 WHERE enterprise_id=:enterpriseId
                """, parameters);
        jdbc.update("""
                UPDATE organization_unit
                   SET name=CASE
                         WHEN community_id=:communityId THEN CONCAT(:projectName, '项目部')
                         WHEN community_id IS NULL THEN CONCAT(:companyName, '总部')
                         ELSE name END,
                       status=CASE WHEN community_id IS NULL OR community_id=:communityId THEN 'ACTIVE' ELSE 'INACTIVE' END,
                       version=version+1, updated_at=:now
                 WHERE enterprise_id=:enterpriseId
                """, parameters);
        jdbc.update("""
                INSERT INTO sys_user
                    (id, username, password_hash, display_name, enabled, password_change_required,
                     version, created_at, updated_at)
                VALUES
                    (:userId, :username, :passwordHash, :displayName, TRUE, FALSE, 0, :now, :now)
                """, parameters);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (:userId, :roleId)", parameters);
        jdbc.update("""
                INSERT INTO sys_user_project_scope (user_id, community_id, data_scope)
                VALUES (:userId, :communityId, 'PROJECT')
                """, parameters);
        jdbc.update("""
                UPDATE system_setup
                   SET initialized=TRUE, deployment_mode='SINGLE_PROJECT', enterprise_id=:enterpriseId,
                       community_id=:communityId, initialized_by=:userId, initialized_at=:now,
                       version=version+1, updated_at=:now
                 WHERE singleton_id=1 AND initialized=FALSE
                """, parameters);
        jdbc.update("""
                INSERT INTO audit_event
                    (id, actor_user_id, community_id, action_code, resource_type, resource_id,
                     request_id, result_status, detail_json, occurred_at)
                VALUES
                    (:auditId, :userId, :communityId, 'system:initialize', 'system_setup', '1',
                     :requestId, 'SUCCESS',
                     JSON_OBJECT('deploymentMode','SINGLE_PROJECT','companyName',:companyName,'projectName',:projectName),
                     :now)
                """, parameters.addValue("auditId", UUID.randomUUID().toString())
                .addValue("requestId", "first-run-" + UUID.randomUUID()));

        return new SetupResult(true, "SINGLE_PROJECT", enterpriseId, companyName,
                communityId, projectName, username);
    }

    private String normalizeName(String value, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("INVALID_SETUP_VALUE", fieldName + "不能为空或包含控制字符",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return normalized;
    }

    private String requiredId(String sql) {
        return jdbc.query(sql, Map.of(), rs -> {
            if (!rs.next()) {
                throw new BusinessException("SETUP_BASELINE_MISSING", "初始化基线不存在，请重新部署干净数据库",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return rs.getString(1);
        });
    }

    public record SetupStatus(boolean initialized, String deploymentMode,
                              String companyName, String projectName) {}

    public record SetupResult(boolean initialized, String deploymentMode,
                              String enterpriseId, String companyName,
                              String projectId, String projectName, String adminUsername) {}
}
