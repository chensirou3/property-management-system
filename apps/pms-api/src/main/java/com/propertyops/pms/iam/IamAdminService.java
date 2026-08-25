package com.propertyops.pms.iam;

import static com.propertyops.pms.iam.IamModels.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class IamAdminService {
    private static final String PLATFORM_ADMIN_ROLE_ID = "10000000-0000-0000-0000-000000000001";

    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextService security;
    private final AuditService audit;

    public IamAdminService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                           SecurityContextService security, AuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.security = security;
        this.audit = audit;
    }

    public List<EnterpriseView> enterprises() {
        requireRead();
        return jdbc.query("""
                SELECT id, code, name, status, version, created_at, updated_at
                  FROM enterprise ORDER BY name
                """, Map.of(), (rs, row) -> new EnterpriseView(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("status"),
                rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    @Transactional
    public EnterpriseView createEnterprise(CreateEnterpriseRequest request) {
        requireWrite();
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        try {
            jdbc.update("""
                    INSERT INTO enterprise (id, code, name, status, version, created_at, updated_at)
                    VALUES (:id, :code, :name, 'ACTIVE', 0, :now, :now)
                    """, Map.of("id", id, "code", request.code(), "name", request.name(), "now", now));
        } catch (DataIntegrityViolationException error) {
            throw duplicate("企业编码已存在");
        }
        audit.success(null, "iam:enterprise:create", "enterprise", id, Map.of("code", request.code()));
        return enterprise(id);
    }

    @Transactional
    public EnterpriseView updateEnterprise(String id, UpdateEnterpriseRequest request) {
        requireWrite();
        int changed = jdbc.update("""
                UPDATE enterprise SET name=:name, status=:status, version=version+1, updated_at=:now
                 WHERE id=:id AND version=:version
                """, Map.of("id", id, "name", request.name(), "status", request.status(),
                "version", request.expectedVersion(), "now", now()));
        requireChanged(changed);
        audit.success(null, "iam:enterprise:update", "enterprise", id,
                Map.of("version", request.expectedVersion()));
        return enterprise(id);
    }

    public List<OrganizationView> organizations(String enterpriseId) {
        requireRead();
        var parameters = new MapSqlParameterSource();
        String where = "";
        if (hasText(enterpriseId)) {
            where = " WHERE enterprise_id=:enterpriseId";
            parameters.addValue("enterpriseId", enterpriseId);
        }
        return jdbc.query("""
                SELECT id, enterprise_id, parent_id, community_id, code, name, organization_type, sort_order,
                       status, version, created_at, updated_at
                  FROM organization_unit
                """ + where + " ORDER BY sort_order, name", parameters, (rs, row) -> new OrganizationView(
                rs.getString("id"), rs.getString("enterprise_id"), rs.getString("parent_id"),
                rs.getString("community_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("organization_type"), rs.getInt("sort_order"), rs.getString("status"),
                rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    @Transactional
    public OrganizationView createOrganization(CreateOrganizationRequest request) {
        requireWrite();
        validateOrganizationReferences(request.enterpriseId(), request.parentId(), request.communityId(), null);
        String id = UUID.randomUUID().toString();
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("enterpriseId", request.enterpriseId())
                .addValue("parentId", blankToNull(request.parentId())).addValue("communityId", blankToNull(request.communityId()))
                .addValue("code", request.code()).addValue("name", request.name())
                .addValue("type", request.organizationType()).addValue("sortOrder", request.sortOrder())
                .addValue("now", now());
        try {
            jdbc.update("""
                    INSERT INTO organization_unit
                        (id, enterprise_id, parent_id, community_id, code, name, organization_type, sort_order,
                         status, version, created_at, updated_at)
                    VALUES (:id, :enterpriseId, :parentId, :communityId, :code, :name, :type, :sortOrder,
                            'ACTIVE', 0, :now, :now)
                    """, params);
        } catch (DataIntegrityViolationException error) {
            throw duplicate("组织编码已存在或关联无效");
        }
        audit.success(request.communityId(), "iam:organization:create", "organization", id,
                Map.of("code", request.code()));
        return organization(id);
    }

    @Transactional
    public OrganizationView updateOrganization(String id, UpdateOrganizationRequest request) {
        requireWrite();
        OrganizationView current = organization(id);
        if (id.equals(request.parentId())) {
            throw new BusinessException("ORGANIZATION_CYCLE", "组织不能把自身设为上级", HttpStatus.CONFLICT);
        }
        validateOrganizationReferences(current.enterpriseId(), request.parentId(), request.communityId(), id);
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("parentId", blankToNull(request.parentId()))
                .addValue("communityId", blankToNull(request.communityId())).addValue("name", request.name())
                .addValue("type", request.organizationType()).addValue("sortOrder", request.sortOrder())
                .addValue("status", request.status()).addValue("version", request.expectedVersion())
                .addValue("now", now());
        int changed = jdbc.update("""
                UPDATE organization_unit
                   SET parent_id=:parentId, community_id=:communityId, name=:name, organization_type=:type,
                       sort_order=:sortOrder, status=:status, version=version+1, updated_at=:now
                 WHERE id=:id AND version=:version
                """, params);
        requireChanged(changed);
        audit.success(request.communityId(), "iam:organization:update", "organization", id,
                Map.of("version", request.expectedVersion()));
        return organization(id);
    }

    public List<PositionView> positions(String enterpriseId) {
        requireRead();
        var params = new MapSqlParameterSource();
        String where = "";
        if (hasText(enterpriseId)) {
            where = " WHERE enterprise_id=:enterpriseId";
            params.addValue("enterpriseId", enterpriseId);
        }
        return jdbc.query("""
                SELECT id, enterprise_id, organization_id, code, name, description, status, version,
                       created_at, updated_at FROM org_position
                """ + where + " ORDER BY name", params, (rs, row) -> new PositionView(
                rs.getString("id"), rs.getString("enterprise_id"), rs.getString("organization_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getString("status"),
                rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    @Transactional
    public PositionView createPosition(CreatePositionRequest request) {
        requireWrite();
        requireOrganizationEnterprise(request.organizationId(), request.enterpriseId());
        String id = UUID.randomUUID().toString();
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("enterpriseId", request.enterpriseId())
                .addValue("organizationId", request.organizationId()).addValue("code", request.code())
                .addValue("name", request.name()).addValue("description", blankToNull(request.description()))
                .addValue("now", now());
        try {
            jdbc.update("""
                    INSERT INTO org_position
                        (id, enterprise_id, organization_id, code, name, description, status, version, created_at, updated_at)
                    VALUES (:id, :enterpriseId, :organizationId, :code, :name, :description, 'ACTIVE', 0, :now, :now)
                    """, params);
        } catch (DataIntegrityViolationException error) {
            throw duplicate("岗位编码已存在或关联无效");
        }
        audit.success(null, "iam:position:create", "position", id, Map.of("code", request.code()));
        return position(id);
    }

    @Transactional
    public PositionView updatePosition(String id, UpdatePositionRequest request) {
        requireWrite();
        PositionView current = position(id);
        requireOrganizationEnterprise(request.organizationId(), current.enterpriseId());
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("organizationId", request.organizationId())
                .addValue("name", request.name()).addValue("description", blankToNull(request.description()))
                .addValue("status", request.status()).addValue("version", request.expectedVersion())
                .addValue("now", now());
        int changed = jdbc.update("""
                UPDATE org_position SET organization_id=:organizationId, name=:name, description=:description,
                       status=:status, version=version+1, updated_at=:now
                 WHERE id=:id AND version=:version
                """, params);
        requireChanged(changed);
        audit.success(null, "iam:position:update", "position", id, Map.of("version", request.expectedVersion()));
        return position(id);
    }

    public List<EmployeeView> employees(String enterpriseId) {
        requireRead();
        var params = new MapSqlParameterSource();
        String where = "";
        if (hasText(enterpriseId)) {
            where = " WHERE enterprise_id=:enterpriseId";
            params.addValue("enterpriseId", enterpriseId);
        }
        return jdbc.query("""
                SELECT id, enterprise_id, organization_id, position_id, employee_no, display_name, mobile_masked,
                       employment_status, hire_date, leave_date, version, created_at, updated_at FROM employee
                """ + where + " ORDER BY display_name", params, (rs, row) -> new EmployeeView(
                rs.getString("id"), rs.getString("enterprise_id"), rs.getString("organization_id"),
                rs.getString("position_id"), rs.getString("employee_no"), rs.getString("display_name"),
                rs.getString("mobile_masked"), rs.getString("employment_status"),
                rs.getDate("hire_date") == null ? null : rs.getDate("hire_date").toLocalDate(),
                rs.getDate("leave_date") == null ? null : rs.getDate("leave_date").toLocalDate(),
                rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    @Transactional
    public EmployeeView createEmployee(CreateEmployeeRequest request) {
        requireWrite();
        validateEmployeeReferences(request.enterpriseId(), request.organizationId(), request.positionId());
        String id = UUID.randomUUID().toString();
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("enterpriseId", request.enterpriseId())
                .addValue("organizationId", request.organizationId()).addValue("positionId", blankToNull(request.positionId()))
                .addValue("employeeNo", request.employeeNo()).addValue("displayName", request.displayName())
                .addValue("mobileMasked", blankToNull(request.mobileMasked())).addValue("hireDate", request.hireDate())
                .addValue("now", now());
        try {
            jdbc.update("""
                    INSERT INTO employee
                        (id, enterprise_id, organization_id, position_id, employee_no, display_name, mobile_masked,
                         employment_status, hire_date, leave_date, version, created_at, updated_at)
                    VALUES (:id, :enterpriseId, :organizationId, :positionId, :employeeNo, :displayName, :mobileMasked,
                            'ACTIVE', :hireDate, NULL, 0, :now, :now)
                    """, params);
        } catch (DataIntegrityViolationException error) {
            throw duplicate("员工编号已存在或关联无效");
        }
        audit.success(null, "iam:employee:create", "employee", id, Map.of("employeeNo", request.employeeNo()));
        return employee(id);
    }

    @Transactional
    public EmployeeView updateEmployee(String id, UpdateEmployeeRequest request) {
        requireWrite();
        EmployeeView current = employee(id);
        validateEmployeeReferences(current.enterpriseId(), request.organizationId(), request.positionId());
        if ("LEFT".equals(request.employmentStatus()) && request.leaveDate() == null) {
            throw new BusinessException("LEAVE_DATE_REQUIRED", "离职员工必须填写离职日期", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("organizationId", request.organizationId())
                .addValue("positionId", blankToNull(request.positionId())).addValue("displayName", request.displayName())
                .addValue("mobileMasked", blankToNull(request.mobileMasked()))
                .addValue("employmentStatus", request.employmentStatus()).addValue("hireDate", request.hireDate())
                .addValue("leaveDate", request.leaveDate()).addValue("version", request.expectedVersion())
                .addValue("now", now());
        int changed = jdbc.update("""
                UPDATE employee SET organization_id=:organizationId, position_id=:positionId,
                       display_name=:displayName, mobile_masked=:mobileMasked,
                       employment_status=:employmentStatus, hire_date=:hireDate, leave_date=:leaveDate,
                       version=version+1, updated_at=:now
                 WHERE id=:id AND version=:version
                """, params);
        requireChanged(changed);
        audit.success(null, "iam:employee:update", "employee", id, Map.of("version", request.expectedVersion()));
        return employee(id);
    }

    public List<PermissionView> permissions() {
        requireRead();
        return jdbc.query("""
                SELECT id, code, name, resource_type FROM sys_permission ORDER BY resource_type, code
                """, Map.of(), (rs, row) -> new PermissionView(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("resource_type")));
    }

    public List<RoleView> roles() {
        requireRead();
        return jdbc.query("""
                SELECT id, enterprise_id, code, name, description, enabled, version, created_at, updated_at
                  FROM sys_role ORDER BY code
                """, Map.of(), (rs, row) -> roleFromRow(rs.getString("id"), rs.getString("enterprise_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getBoolean("enabled"),
                rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    @Transactional
    public RoleView createRole(CreateRoleRequest request) {
        requireWrite();
        validatePermissionIds(request.permissionIds());
        String id = UUID.randomUUID().toString();
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("enterpriseId", blankToNull(request.enterpriseId()))
                .addValue("code", request.code()).addValue("name", request.name())
                .addValue("description", blankToNull(request.description())).addValue("now", now());
        try {
            jdbc.update("""
                    INSERT INTO sys_role
                        (id, enterprise_id, code, name, description, enabled, version, created_at, updated_at)
                    VALUES (:id, :enterpriseId, :code, :name, :description, TRUE, 0, :now, :now)
                    """, params);
            replaceRolePermissions(id, request.permissionIds());
        } catch (DataIntegrityViolationException error) {
            throw duplicate("角色编码已存在或权限关联无效");
        }
        audit.success(null, "iam:role:create", "role", id, Map.of("code", request.code()));
        return role(id);
    }

    @Transactional
    public RoleView updateRole(String id, UpdateRoleRequest request) {
        requireWrite();
        validatePermissionIds(request.permissionIds());
        if (PLATFORM_ADMIN_ROLE_ID.equals(id)) {
            if (!request.enabled() || !permissionCodes(request.permissionIds()).containsAll(Set.of("iam:read", "iam:write"))) {
                throw new BusinessException("PLATFORM_ADMIN_PROTECTED", "平台管理员角色必须保持启用并保留 IAM 权限",
                        HttpStatus.CONFLICT);
            }
        }
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("name", request.name())
                .addValue("description", blankToNull(request.description())).addValue("enabled", request.enabled())
                .addValue("version", request.expectedVersion()).addValue("now", now());
        int changed = jdbc.update("""
                UPDATE sys_role SET name=:name, description=:description, enabled=:enabled,
                       version=version+1, updated_at=:now WHERE id=:id AND version=:version
                """, params);
        requireChanged(changed);
        replaceRolePermissions(id, request.permissionIds());
        audit.success(null, "iam:role:update", "role", id, Map.of("version", request.expectedVersion()));
        return role(id);
    }

    public List<UserView> users() {
        requireRead();
        return jdbc.query("""
                SELECT id, username, display_name, employee_id, enabled, password_change_required, version,
                       last_login_at, created_at, updated_at FROM sys_user ORDER BY username
                """, Map.of(), (rs, row) -> userFromRow(rs.getString("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("employee_id"), rs.getBoolean("enabled"),
                rs.getBoolean("password_change_required"), rs.getLong("version"),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    @Transactional
    public UserView createUser(CreateUserRequest request) {
        requireWrite();
        validateAccess(request.employeeId(), request.roleIds(), request.projectIds());
        String id = UUID.randomUUID().toString();
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("username", request.username())
                .addValue("passwordHash", passwordEncoder.encode(request.password()))
                .addValue("displayName", request.displayName()).addValue("employeeId", blankToNull(request.employeeId()))
                .addValue("enabled", request.enabled()).addValue("now", now());
        try {
            jdbc.update("""
                    INSERT INTO sys_user
                        (id, username, password_hash, display_name, employee_id, enabled, password_change_required,
                         version, created_at, updated_at)
                    VALUES (:id, :username, :passwordHash, :displayName, :employeeId, :enabled, TRUE, 0, :now, :now)
                    """, params);
            replaceUserAccess(id, request.roleIds(), request.projectIds());
        } catch (DataIntegrityViolationException error) {
            throw duplicate("账号已存在、员工已绑定账号或授权关联无效");
        }
        audit.success(null, "iam:user:create", "user", id,
                Map.of("username", request.username(), "roleCount", request.roleIds().size(),
                        "projectCount", request.projectIds().size()));
        return user(id);
    }

    @Transactional
    public UserView updateUser(String id, UpdateUserRequest request) {
        requireWrite();
        validateAccess(request.employeeId(), request.roleIds(), request.projectIds());
        if (id.equals(security.requirePrincipal().userId()) && !request.enabled()) {
            throw new BusinessException("SELF_DISABLE_FORBIDDEN", "不能停用当前登录账号", HttpStatus.CONFLICT);
        }
        if (hasPlatformAdminRole(id) && !request.roleIds().contains(PLATFORM_ADMIN_ROLE_ID)) {
            long enabledAdmins = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id
                     WHERE ur.role_id=:roleId AND u.enabled=TRUE
                    """, Map.of("roleId", PLATFORM_ADMIN_ROLE_ID), Long.class);
            if (enabledAdmins <= 1) {
                throw new BusinessException("LAST_ADMIN_PROTECTED", "不能移除最后一个启用的平台管理员",
                        HttpStatus.CONFLICT);
            }
        }
        var params = new MapSqlParameterSource()
                .addValue("id", id).addValue("displayName", request.displayName())
                .addValue("employeeId", blankToNull(request.employeeId())).addValue("enabled", request.enabled())
                .addValue("passwordChangeRequired", request.passwordChangeRequired())
                .addValue("version", request.expectedVersion()).addValue("now", now());
        try {
            int changed = jdbc.update("""
                    UPDATE sys_user SET display_name=:displayName, employee_id=:employeeId, enabled=:enabled,
                           password_change_required=:passwordChangeRequired, version=version+1, updated_at=:now
                     WHERE id=:id AND version=:version
                    """, params);
            requireChanged(changed);
            replaceUserAccess(id, request.roleIds(), request.projectIds());
        } catch (DataIntegrityViolationException error) {
            throw duplicate("员工已绑定其他账号或授权关联无效");
        }
        audit.success(null, "iam:user:update", "user", id,
                Map.of("version", request.expectedVersion(), "roleCount", request.roleIds().size(),
                        "projectCount", request.projectIds().size()));
        return user(id);
    }

    @Transactional
    public UserView resetPassword(String id, ResetPasswordRequest request) {
        requireWrite();
        int changed = jdbc.update("""
                UPDATE sys_user SET password_hash=:passwordHash, password_change_required=:requireChange,
                       version=version+1, updated_at=:now WHERE id=:id AND version=:version
                """, Map.of("id", id, "passwordHash", passwordEncoder.encode(request.password()),
                "requireChange", request.requireChange(), "version", request.expectedVersion(), "now", now()));
        requireChanged(changed);
        audit.success(null, "iam:user:password-reset", "user", id,
                Map.of("requireChange", request.requireChange(), "version", request.expectedVersion()));
        return user(id);
    }

    public List<ProjectView> projects() {
        requireRead();
        return jdbc.query("""
                SELECT id, enterprise_id, name, status FROM community ORDER BY name
                """, Map.of(), (rs, row) -> new ProjectView(rs.getString("id"), rs.getString("enterprise_id"),
                rs.getString("name"), rs.getString("status")));
    }

    private EnterpriseView enterprise(String id) {
        return single("""
                SELECT id, code, name, status, version, created_at, updated_at FROM enterprise WHERE id=:id
                """, id, (rs, row) -> new EnterpriseView(rs.getString("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    private OrganizationView organization(String id) {
        return single("""
                SELECT id, enterprise_id, parent_id, community_id, code, name, organization_type, sort_order,
                       status, version, created_at, updated_at FROM organization_unit WHERE id=:id
                """, id, (rs, row) -> new OrganizationView(rs.getString("id"), rs.getString("enterprise_id"),
                rs.getString("parent_id"), rs.getString("community_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("organization_type"), rs.getInt("sort_order"), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    private PositionView position(String id) {
        return single("""
                SELECT id, enterprise_id, organization_id, code, name, description, status, version,
                       created_at, updated_at FROM org_position WHERE id=:id
                """, id, (rs, row) -> new PositionView(rs.getString("id"), rs.getString("enterprise_id"),
                rs.getString("organization_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    private EmployeeView employee(String id) {
        return single("""
                SELECT id, enterprise_id, organization_id, position_id, employee_no, display_name, mobile_masked,
                       employment_status, hire_date, leave_date, version, created_at, updated_at
                  FROM employee WHERE id=:id
                """, id, (rs, row) -> new EmployeeView(rs.getString("id"), rs.getString("enterprise_id"),
                rs.getString("organization_id"), rs.getString("position_id"), rs.getString("employee_no"),
                rs.getString("display_name"), rs.getString("mobile_masked"), rs.getString("employment_status"),
                rs.getDate("hire_date") == null ? null : rs.getDate("hire_date").toLocalDate(),
                rs.getDate("leave_date") == null ? null : rs.getDate("leave_date").toLocalDate(),
                rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    private RoleView role(String id) {
        return single("""
                SELECT id, enterprise_id, code, name, description, enabled, version, created_at, updated_at
                  FROM sys_role WHERE id=:id
                """, id, (rs, row) -> roleFromRow(rs.getString("id"), rs.getString("enterprise_id"),
                rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getBoolean("enabled"),
                rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    private RoleView roleFromRow(String id, String enterpriseId, String code, String name, String description,
                                 boolean enabled, long version, LocalDateTime createdAt, LocalDateTime updatedAt) {
        Set<String> permissionIds = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT permission_id FROM sys_role_permission WHERE role_id=:id ORDER BY permission_id
                """, Map.of("id", id), String.class));
        return new RoleView(id, enterpriseId, code, name, description, enabled, version,
                permissionIds, createdAt, updatedAt);
    }

    private UserView user(String id) {
        return single("""
                SELECT id, username, display_name, employee_id, enabled, password_change_required, version,
                       last_login_at, created_at, updated_at FROM sys_user WHERE id=:id
                """, id, (rs, row) -> userFromRow(rs.getString("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("employee_id"), rs.getBoolean("enabled"),
                rs.getBoolean("password_change_required"), rs.getLong("version"),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()));
    }

    private UserView userFromRow(String id, String username, String displayName, String employeeId,
                                 boolean enabled, boolean passwordChangeRequired, long version,
                                 LocalDateTime lastLoginAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
        Set<String> roleIds = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT role_id FROM sys_user_role WHERE user_id=:id ORDER BY role_id
                """, Map.of("id", id), String.class));
        Set<String> projectIds = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT community_id FROM sys_user_project_scope WHERE user_id=:id ORDER BY community_id
                """, Map.of("id", id), String.class));
        return new UserView(id, username, displayName, employeeId, enabled, passwordChangeRequired, version,
                roleIds, projectIds, lastLoginAt, createdAt, updatedAt);
    }

    private <T> T single(String sql, String id, org.springframework.jdbc.core.RowMapper<T> mapper) {
        List<T> rows = jdbc.query(sql, Map.of("id", id), mapper);
        if (rows.isEmpty()) {
            throw new BusinessException("IAM_RECORD_NOT_FOUND", "记录不存在", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private void validateOrganizationReferences(String enterpriseId, String parentId, String communityId, String currentId) {
        requireCount("SELECT COUNT(*) FROM enterprise WHERE id=:id", enterpriseId, "企业不存在");
        if (hasText(parentId)) {
            long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM organization_unit WHERE id=:id AND enterprise_id=:enterpriseId
                    """, Map.of("id", parentId, "enterpriseId", enterpriseId), Long.class);
            if (count != 1) invalidReference("上级组织不属于当前企业");
            if (hasText(currentId)) {
                long descendants = jdbc.queryForObject("""
                        WITH RECURSIVE descendants AS (
                            SELECT id FROM organization_unit WHERE parent_id=:currentId
                            UNION ALL
                            SELECT o.id FROM organization_unit o JOIN descendants d ON o.parent_id=d.id
                        ) SELECT COUNT(*) FROM descendants WHERE id=:parentId
                        """, Map.of("currentId", currentId, "parentId", parentId), Long.class);
                if (descendants > 0) {
                    throw new BusinessException("ORGANIZATION_CYCLE", "不能把下级组织设为上级", HttpStatus.CONFLICT);
                }
            }
        }
        if (hasText(communityId)) {
            long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM community WHERE id=:id AND enterprise_id=:enterpriseId
                    """, Map.of("id", communityId, "enterpriseId", enterpriseId), Long.class);
            if (count != 1) invalidReference("项目不属于当前企业");
        }
    }

    private void requireOrganizationEnterprise(String organizationId, String enterpriseId) {
        long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM organization_unit WHERE id=:id AND enterprise_id=:enterpriseId
                """, Map.of("id", organizationId, "enterpriseId", enterpriseId), Long.class);
        if (count != 1) invalidReference("组织不属于当前企业");
    }

    private void validateEmployeeReferences(String enterpriseId, String organizationId, String positionId) {
        requireOrganizationEnterprise(organizationId, enterpriseId);
        if (hasText(positionId)) {
            long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM org_position
                     WHERE id=:id AND enterprise_id=:enterpriseId AND organization_id=:organizationId
                    """, Map.of("id", positionId, "enterpriseId", enterpriseId, "organizationId", organizationId),
                    Long.class);
            if (count != 1) invalidReference("岗位不属于所选组织");
        }
    }

    private void validateAccess(String employeeId, Set<String> roleIds, Set<String> projectIds) {
        if (hasText(employeeId)) requireCount("SELECT COUNT(*) FROM employee WHERE id=:id", employeeId, "员工不存在");
        if (roleIds.isEmpty()) {
            throw new BusinessException("ROLE_REQUIRED", "账号至少需要一个角色", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        validateIds("sys_role", roleIds, "存在无效角色");
        validateIds("community", projectIds, "存在无效项目范围");
    }

    private void validatePermissionIds(Set<String> permissionIds) {
        validateIds("sys_permission", permissionIds, "存在无效权限");
    }

    private void validateIds(String table, Set<String> ids, String message) {
        if (ids.isEmpty()) return;
        var params = new MapSqlParameterSource("ids", ids);
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id IN (:ids)", params, Long.class);
        if (count != ids.size()) invalidReference(message);
    }

    private Set<String> permissionCodes(Set<String> ids) {
        if (ids.isEmpty()) return Set.of();
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT code FROM sys_permission WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), String.class));
    }

    private void replaceRolePermissions(String roleId, Set<String> permissionIds) {
        jdbc.update("DELETE FROM sys_role_permission WHERE role_id=:id", Map.of("id", roleId));
        for (String permissionId : permissionIds) {
            jdbc.update("""
                    INSERT INTO sys_role_permission (role_id, permission_id) VALUES (:roleId, :permissionId)
                    """, Map.of("roleId", roleId, "permissionId", permissionId));
        }
    }

    private void replaceUserAccess(String userId, Set<String> roleIds, Set<String> projectIds) {
        jdbc.update("DELETE FROM sys_user_role WHERE user_id=:id", Map.of("id", userId));
        for (String roleId : roleIds) {
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (:userId, :roleId)",
                    Map.of("userId", userId, "roleId", roleId));
        }
        jdbc.update("DELETE FROM sys_user_project_scope WHERE user_id=:id", Map.of("id", userId));
        for (String projectId : projectIds) {
            jdbc.update("""
                    INSERT INTO sys_user_project_scope (user_id, community_id, data_scope)
                    VALUES (:userId, :projectId, 'PROJECT')
                    """, Map.of("userId", userId, "projectId", projectId));
        }
    }

    private boolean hasPlatformAdminRole(String userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_user_role WHERE user_id=:userId AND role_id=:roleId
                """, Map.of("userId", userId, "roleId", PLATFORM_ADMIN_ROLE_ID), Long.class);
        return count != null && count > 0;
    }

    private void requireCount(String sql, String id, String message) {
        Long count = jdbc.queryForObject(sql, Map.of("id", id), Long.class);
        if (count == null || count != 1) invalidReference(message);
    }

    private void requireRead() {
        security.requirePermission("iam:read");
    }

    private void requireWrite() {
        security.requirePermission("iam:write");
    }

    private void requireChanged(int changed) {
        if (changed == 0) {
            throw new BusinessException("OPTIMISTIC_LOCK_CONFLICT", "数据已更新，请刷新后重试", HttpStatus.CONFLICT);
        }
    }

    private BusinessException duplicate(String message) {
        return new BusinessException("IAM_DUPLICATE_OR_INVALID_REFERENCE", message, HttpStatus.CONFLICT);
    }

    private void invalidReference(String message) {
        throw new BusinessException("IAM_INVALID_REFERENCE", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
