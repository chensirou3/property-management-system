INSERT INTO sys_permission (id, code, name, resource_type, created_at) VALUES
    (UUID(), 'bank:read', '查看银行托收', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'bank:write', '执行银行托收', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'bank:import', '导入银行结果', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'bank:export', '导出银行对账', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'cashier:print', '打印收费凭证', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'dashboard:configure', '配置业务看板', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'dashboard:export', '导出看板数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'dictionary:read', '查看数据字典', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'dictionary:write', '维护数据字典', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'dictionary:import', '导入数据字典', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'dictionary:export', '导出数据字典', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'fee:discount-write', '维护费用折扣', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'fee:import', '导入费用配置', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'fee:export', '导出费用数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:read', '查看财务账务', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:write', '维护财务账务', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:adjust', '执行财务调账', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:deposit-write', '执行押金收退', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:instrument-write', '维护票据库存', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:offset', '执行预收抵扣', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:reverse', '执行交易冲正', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:import', '导入财务数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:export', '导出财务数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'finance:print', '打印财务凭证', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'iam:export', '导出组织权限数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'integration:read', '查看第三方参数', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'integration:write', '维护第三方参数', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'invoice:read', '查看发票业务', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'invoice:write', '执行发票业务', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'invoice:export', '导出发票数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'invoice:print', '打印发票资料', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'meter:import', '导入抄表数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'meter:export', '导出抄表数据', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'migration:read', '查看数据迁移', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'migration:write', '执行数据迁移', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'migration:import', '上传迁移文件', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'migration:export', '导出迁移结果', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'notification:read', '查看通知任务', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'notification:send', '发送业务通知', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'notification:import', '导入通知名单', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'notification:export', '导出通知结果', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'notification:print', '打印通知明细', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'property:import', '导入基础档案', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'property:export', '导出基础档案', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'property:export-sensitive', '导出敏感基础档案', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'property:print', '打印基础档案', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'report:read', '查看业务报表', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'report:export', '导出业务报表', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'report:print', '打印业务报表', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'visitor:read', '查看访客记录', 'PAGE', CURRENT_TIMESTAMP(3)),
    (UUID(), 'visitor:write', '维护访客记录', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'visitor:import', '导入访客记录', 'BUTTON', CURRENT_TIMESTAMP(3)),
    (UUID(), 'visitor:export-sensitive', '导出敏感访客记录', 'BUTTON', CURRENT_TIMESTAMP(3));

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000001', permission.id
  FROM sys_permission permission
 WHERE permission.code IN (
    'bank:read', 'bank:write', 'bank:import', 'bank:export', 'cashier:print',
    'dashboard:configure', 'dashboard:export', 'dictionary:read', 'dictionary:write', 'dictionary:import', 'dictionary:export',
    'fee:discount-write', 'fee:import', 'fee:export',
    'finance:read', 'finance:write', 'finance:adjust', 'finance:deposit-write', 'finance:instrument-write', 'finance:offset',
    'finance:reverse', 'finance:import', 'finance:export', 'finance:print', 'iam:export',
    'integration:read', 'integration:write', 'invoice:read', 'invoice:write', 'invoice:export', 'invoice:print',
    'meter:import', 'meter:export', 'migration:read', 'migration:write', 'migration:import', 'migration:export',
    'notification:read', 'notification:send', 'notification:import', 'notification:export', 'notification:print',
    'property:import', 'property:export', 'property:export-sensitive', 'property:print',
    'report:read', 'report:export', 'report:print',
    'visitor:read', 'visitor:write', 'visitor:import', 'visitor:export-sensitive'
 );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000002', permission.id
  FROM sys_permission permission
 WHERE permission.code IN (
    'dashboard:configure', 'dashboard:export', 'dictionary:read',
    'fee:discount-write', 'fee:import', 'fee:export',
    'finance:read', 'finance:export', 'finance:print',
    'invoice:read', 'invoice:export', 'invoice:print',
    'meter:import', 'meter:export', 'notification:read', 'notification:send', 'notification:export', 'notification:print',
    'property:import', 'property:export', 'property:print', 'report:read', 'report:export', 'report:print',
    'visitor:read'
 );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000003', permission.id
  FROM sys_permission permission
 WHERE permission.code IN (
    'cashier:print', 'finance:read', 'finance:deposit-write', 'finance:offset', 'finance:reverse', 'finance:export', 'finance:print',
    'invoice:read', 'invoice:write', 'invoice:export', 'invoice:print',
    'notification:read', 'notification:send', 'notification:export', 'notification:print',
    'report:read', 'report:export', 'report:print'
 );

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT '10000000-0000-0000-0000-000000000004', permission.id
  FROM sys_permission permission
 WHERE permission.code IN ('meter:import', 'meter:export');
