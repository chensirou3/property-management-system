INSERT INTO sys_role (id, code, name, created_at) VALUES
('10000000-0000-0000-0000-000000000001', 'PLATFORM_ADMIN', '平台管理员', CURRENT_TIMESTAMP(3)),
('10000000-0000-0000-0000-000000000002', 'PROJECT_MANAGER', '项目管理员', CURRENT_TIMESTAMP(3)),
('10000000-0000-0000-0000-000000000003', 'CASHIER', '收银员', CURRENT_TIMESTAMP(3)),
('10000000-0000-0000-0000-000000000004', 'METER_READER', '抄表员', CURRENT_TIMESTAMP(3));

INSERT INTO sys_permission (id, code, name, resource_type, created_at) VALUES
('20000000-0000-0000-0000-000000000001', 'dashboard:read', '查看工作台', 'PAGE', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000002', 'property:read', '查看基础档案', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000003', 'property:write', '维护基础档案', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000004', 'fee:read', '查看费用配置', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000005', 'fee:write', '维护费用配置', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000006', 'cashier:read', '查看收银账务', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000007', 'cashier:write', '执行收款', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000008', 'meter:read', '查看仪表抄表', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000009', 'meter:write', '执行抄表', 'API', CURRENT_TIMESTAMP(3)),
('20000000-0000-0000-0000-000000000010', 'system:audit', '查看操作审计', 'API', CURRENT_TIMESTAMP(3));

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000001', id FROM sys_permission;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000002', id FROM sys_permission
WHERE code IN ('dashboard:read', 'property:read', 'property:write', 'fee:read', 'fee:write', 'cashier:read', 'meter:read');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000003', id FROM sys_permission
WHERE code IN ('dashboard:read', 'property:read', 'fee:read', 'cashier:read', 'cashier:write');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000004', id FROM sys_permission
WHERE code IN ('dashboard:read', 'property:read', 'meter:read', 'meter:write');

