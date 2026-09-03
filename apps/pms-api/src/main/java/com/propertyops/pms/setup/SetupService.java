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

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String enterpriseId = optionalId(
                "SELECT id FROM enterprise ORDER BY created_at, id LIMIT 1 FOR UPDATE", Map.of());
        boolean createEnterprise = enterpriseId == null;
        if (createEnterprise) {
            enterpriseId = UUID.randomUUID().toString();
        }
        String communityId = optionalId("""
                SELECT id FROM community
                 WHERE enterprise_id=:enterpriseId
                 ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """, Map.of("enterpriseId", enterpriseId));
        boolean createCommunity = communityId == null;
        if (createCommunity) {
            communityId = UUID.randomUUID().toString();
        }
        String userId = UUID.randomUUID().toString();

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

        if (createEnterprise) {
            jdbc.update("""
                    INSERT INTO enterprise
                        (id, code, name, status, version, created_at, updated_at)
                    VALUES
                        (:enterpriseId, 'LOCAL-ENTERPRISE-001', :companyName, 'ACTIVE', 0, :now, :now)
                    """, parameters);
        } else {
            jdbc.update("""
                    UPDATE enterprise
                       SET name=:companyName, status='ACTIVE', version=version+1, updated_at=:now
                     WHERE id=:enterpriseId
                    """, parameters);
        }

        if (createCommunity) {
            jdbc.update("""
                    INSERT INTO community
                        (id, enterprise_id, source_system, source_id, name, managed_area, status,
                         version, created_at, updated_at)
                    VALUES
                        (:communityId, :enterpriseId, 'LOCAL_SETUP', 'LOCAL-COMMUNITY-001',
                         :projectName, 0, 'ACTIVE', 0, :now, :now)
                    """, parameters);
        } else {
            jdbc.update("""
                    UPDATE community
                       SET name=CASE WHEN id=:communityId THEN :projectName ELSE name END,
                           source_system=CASE WHEN id=:communityId THEN 'LOCAL_SETUP' ELSE source_system END,
                           source_id=CASE WHEN id=:communityId THEN 'LOCAL-COMMUNITY-001' ELSE source_id END,
                           status=CASE WHEN id=:communityId THEN 'ACTIVE' ELSE 'INACTIVE' END,
                           version=version+1, updated_at=:now
                     WHERE enterprise_id=:enterpriseId
                    """, parameters);
        }

        String headquartersId = optionalId("""
                SELECT id FROM organization_unit
                 WHERE enterprise_id=:enterpriseId AND community_id IS NULL
                 ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """, Map.of("enterpriseId", enterpriseId));
        if (headquartersId == null) {
            headquartersId = UUID.randomUUID().toString();
            parameters.addValue("headquartersId", headquartersId);
            jdbc.update("""
                    INSERT INTO organization_unit
                        (id, enterprise_id, parent_id, community_id, code, name, organization_type,
                         sort_order, status, version, created_at, updated_at)
                    VALUES
                        (:headquartersId, :enterpriseId, NULL, NULL, 'HQ', CONCAT(:companyName, '总部'),
                         'COMPANY', 10, 'ACTIVE', 0, :now, :now)
                    """, parameters);
        } else {
            parameters.addValue("headquartersId", headquartersId);
            jdbc.update("""
                    UPDATE organization_unit
                       SET name=CONCAT(:companyName, '总部'), status='ACTIVE', version=version+1,
                           updated_at=:now
                     WHERE id=:headquartersId
                    """, parameters);
        }

        String projectOrganizationId = optionalId("""
                SELECT id FROM organization_unit
                 WHERE enterprise_id=:enterpriseId AND community_id=:communityId
                 ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """, Map.of("enterpriseId", enterpriseId, "communityId", communityId));
        if (projectOrganizationId == null) {
            parameters.addValue("projectOrganizationId", UUID.randomUUID().toString());
            jdbc.update("""
                    INSERT INTO organization_unit
                        (id, enterprise_id, parent_id, community_id, code, name, organization_type,
                         sort_order, status, version, created_at, updated_at)
                    VALUES
                        (:projectOrganizationId, :enterpriseId, :headquartersId, :communityId, 'PROJECT',
                         CONCAT(:projectName, '项目部'), 'PROJECT', 20, 'ACTIVE', 0, :now, :now)
                    """, parameters);
        } else {
            parameters.addValue("projectOrganizationId", projectOrganizationId);
            jdbc.update("""
                    UPDATE organization_unit
                       SET parent_id=:headquartersId, name=CONCAT(:projectName, '项目部'), status='ACTIVE',
                           version=version+1, updated_at=:now
                     WHERE id=:projectOrganizationId
                    """, parameters);
        }
        jdbc.update("""
                UPDATE organization_unit
                   SET status=CASE
                         WHEN id IN (:headquartersId, :projectOrganizationId) THEN 'ACTIVE'
                         ELSE 'INACTIVE' END,
                       version=version+1, updated_at=:now
                 WHERE enterprise_id=:enterpriseId
                   AND id NOT IN (:headquartersId, :projectOrganizationId)
                """, parameters);

        jdbc.update("""
                INSERT INTO dashboard_widget_configuration
                    (id, community_id, role_code, widget_code, widget_name, metric_code, position_code,
                     visible, refresh_interval_seconds, display_order, status, published_at, version,
                     created_at, updated_at)
                SELECT UUID(), :communityId, 'ALL', seed.widget_code, seed.widget_name, seed.metric_code,
                       seed.position_code, seed.visible, seed.refresh_seconds, seed.display_order,
                       'PUBLISHED', :now, 0, :now, :now
                  FROM (
                        SELECT 'ASSET_SUMMARY' widget_code, '资产概览' widget_name,
                               'ASSET_COUNTS' metric_code, 'SUMMARY' position_code,
                               TRUE visible, 300 refresh_seconds, 1 display_order
                        UNION ALL SELECT 'FINANCE_OVERVIEW', '收费概览', 'COLLECTION_RATE', 'MAIN', TRUE, 300, 2
                        UNION ALL SELECT 'DATA_QUALITY', '数据质量', 'DATA_QUALITY', 'SIDE', TRUE, 600, 3
                        UNION ALL SELECT 'INTEGRATION_STATUS', '集成状态', 'INTEGRATION_STATUS', 'SIDE', FALSE, 600, 4
                       ) seed
                 WHERE NOT EXISTS (
                       SELECT 1 FROM dashboard_widget_configuration existing
                        WHERE existing.community_id=:communityId
                          AND existing.role_code='ALL'
                          AND existing.widget_code=seed.widget_code
                 )
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

    private String optionalId(String sql, Map<String, ?> parameters) {
        return jdbc.query(sql, parameters, rs -> rs.next() ? rs.getString(1) : null);
    }

    public record SetupStatus(boolean initialized, String deploymentMode,
                              String companyName, String projectName) {}

    public record SetupResult(boolean initialized, String deploymentMode,
                              String enterpriseId, String companyName,
                              String projectId, String projectName, String adminUsername) {}
}
