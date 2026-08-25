CREATE TABLE enterprise (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_enterprise_code UNIQUE (code)
);

INSERT INTO enterprise (id, code, name, status, version, created_at, updated_at)
VALUES ('31000000-0000-0000-0000-000000000001', 'SYNTHETIC_PROPERTY', '合成物业集团', 'ACTIVE', 0,
        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

ALTER TABLE community ADD COLUMN enterprise_id VARCHAR(36) NULL AFTER id;
UPDATE community
   SET enterprise_id = '31000000-0000-0000-0000-000000000001'
 WHERE enterprise_id IS NULL;
ALTER TABLE community MODIFY enterprise_id VARCHAR(36) NOT NULL;
ALTER TABLE community
    ADD CONSTRAINT fk_community_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id);
CREATE INDEX idx_community_enterprise ON community (enterprise_id, status, name);

INSERT INTO community
    (id, enterprise_id, source_system, source_id, name, managed_area, address, province_code, city_code,
     district_code, service_phone, contact_name, status, version, created_at, updated_at)
VALUES
    ('30000000-0000-0000-0000-000000000002', '31000000-0000-0000-0000-000000000001',
     'SYNTHETIC', 'SYN-COMMUNITY-002', '海湾雅居（合成隔离项目）', 128000.00, '合成地址（仅用于项目隔离测试）',
     '370000', '370200', '370211', '400-000-0000', '合成项目负责人', 'ACTIVE', 0,
     CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

CREATE TABLE organization_unit (
    id VARCHAR(36) PRIMARY KEY,
    enterprise_id VARCHAR(36) NOT NULL,
    parent_id VARCHAR(36),
    community_id VARCHAR(36),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    organization_type VARCHAR(30) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_organization_code UNIQUE (enterprise_id, code),
    CONSTRAINT fk_organization_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
    CONSTRAINT fk_organization_parent FOREIGN KEY (parent_id) REFERENCES organization_unit(id),
    CONSTRAINT fk_organization_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE INDEX idx_organization_tree ON organization_unit (enterprise_id, parent_id, sort_order);

CREATE TABLE org_position (
    id VARCHAR(36) PRIMARY KEY,
    enterprise_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_position_code UNIQUE (enterprise_id, code),
    CONSTRAINT fk_position_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
    CONSTRAINT fk_position_organization FOREIGN KEY (organization_id) REFERENCES organization_unit(id)
);

CREATE INDEX idx_position_organization ON org_position (organization_id, status, name);

CREATE TABLE employee (
    id VARCHAR(36) PRIMARY KEY,
    enterprise_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36) NOT NULL,
    position_id VARCHAR(36),
    employee_no VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    mobile_masked VARCHAR(40),
    employment_status VARCHAR(30) NOT NULL,
    hire_date DATE,
    leave_date DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_employee_no UNIQUE (enterprise_id, employee_no),
    CONSTRAINT fk_employee_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id),
    CONSTRAINT fk_employee_organization FOREIGN KEY (organization_id) REFERENCES organization_unit(id),
    CONSTRAINT fk_employee_position FOREIGN KEY (position_id) REFERENCES org_position(id)
);

CREATE INDEX idx_employee_organization ON employee (organization_id, employment_status, display_name);

ALTER TABLE sys_user
    ADD COLUMN employee_id VARCHAR(36) NULL AFTER display_name,
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled,
    ADD COLUMN last_login_at DATETIME(3) NULL AFTER password_change_required,
    ADD CONSTRAINT fk_sys_user_employee FOREIGN KEY (employee_id) REFERENCES employee(id),
    ADD CONSTRAINT uk_sys_user_employee UNIQUE (employee_id);

ALTER TABLE sys_role
    ADD COLUMN enterprise_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN description VARCHAR(500) NULL AFTER name,
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER description,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER enabled,
    ADD COLUMN updated_at DATETIME(3) NULL AFTER created_at,
    ADD CONSTRAINT fk_sys_role_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprise(id);

UPDATE sys_role SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE sys_role MODIFY updated_at DATETIME(3) NOT NULL;

ALTER TABLE sys_user_role
    ADD COLUMN assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

ALTER TABLE sys_user_project_scope
    ADD COLUMN granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

INSERT INTO organization_unit
    (id, enterprise_id, parent_id, community_id, code, name, organization_type, sort_order, status, version,
     created_at, updated_at)
VALUES
    ('32000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001', NULL, NULL,
     'HQ', '集团总部', 'COMPANY', 10, 'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('32000000-0000-0000-0000-000000000002', '31000000-0000-0000-0000-000000000001',
     '32000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
     'YOUSHAN', '优山美地项目部', 'PROJECT', 20, 'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('32000000-0000-0000-0000-000000000003', '31000000-0000-0000-0000-000000000001',
     '32000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     'HAIWAN', '海湾雅居项目部', 'PROJECT', 30, 'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

INSERT INTO org_position
    (id, enterprise_id, organization_id, code, name, description, status, version, created_at, updated_at)
VALUES
    ('33000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
     '32000000-0000-0000-0000-000000000001', 'PLATFORM_OPERATOR', '平台运营', '合成平台运营岗位',
     'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('33000000-0000-0000-0000-000000000002', '31000000-0000-0000-0000-000000000001',
     '32000000-0000-0000-0000-000000000002', 'PROJECT_MANAGER', '项目经理', '合成项目管理岗位',
     'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

INSERT INTO employee
    (id, enterprise_id, organization_id, position_id, employee_no, display_name, mobile_masked,
     employment_status, hire_date, leave_date, version, created_at, updated_at)
VALUES
    ('34000000-0000-0000-0000-000000000001', '31000000-0000-0000-0000-000000000001',
     '32000000-0000-0000-0000-000000000002', '33000000-0000-0000-0000-000000000002',
     'SYN-EMP-001', '合成项目员工', '138****0001', 'ACTIVE', '2026-01-01', NULL, 0,
     CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

INSERT INTO sys_permission (id, code, name, resource_type, created_at) VALUES
    ('20000000-0000-0000-0000-000000000011', 'iam:read', '查看企业组织与权限', 'API', CURRENT_TIMESTAMP(3)),
    ('20000000-0000-0000-0000-000000000012', 'iam:write', '维护企业组织与权限', 'API', CURRENT_TIMESTAMP(3));

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000001', id
  FROM sys_permission
 WHERE code IN ('iam:read', 'iam:write');
