export interface TableColumn {
  prop: string
  label: string
  width?: number
  minWidth?: number
  sortable?: boolean
  sortKey?: string
}

export interface FormField {
  prop: string
  label: string
  type?: 'text' | 'number' | 'select' | 'switch' | 'textarea' | 'date' | 'datetime'
  required?: boolean
  options?: Array<{ label: string; value: string }>
}

export interface PageSchema {
  resource: string
  readPermission: string
  writePermission?: string
  allowCreate?: boolean
  allowArchive?: boolean
  category?: string
  status?: string
  columns: TableColumn[]
  form?: FormField[]
  defaults?: Record<string, unknown>
  description?: string
}

const statusOptions = [
  { label: '启用/正常', value: 'ACTIVE' },
  { label: '停用', value: 'INACTIVE' },
  { label: '未缴', value: 'UNPAID' },
  { label: '部分缴费', value: 'PARTIAL' },
  { label: '已缴', value: 'PAID' },
]

const state = (prop = 'status'): TableColumn => ({ prop, label: '状态', width: 118 })
const updated: TableColumn = { prop: 'updated_at', label: '更新时间', width: 180, sortable: true, sortKey: 'updatedAt' }
const base = (resource: string, permission: string, columns: TableColumn[], extra: Partial<PageSchema> = {}): PageSchema => ({
  resource,
  readPermission: `${permission}:read`,
  columns,
  ...extra,
})

export const pageSchemas: Record<string, PageSchema> = {
  '/archives/communities': base('communities', 'property', [
    { prop: 'name', label: '小区名称', minWidth: 220, sortable: true, sortKey: 'name' },
    { prop: 'managed_area', label: '管理面积（㎡）', width: 150, sortable: true, sortKey: 'managedArea' },
    { prop: 'address', label: '地址', minWidth: 240 }, state(), updated,
  ], {
    writePermission: 'property:write',
    allowCreate: false,
    allowArchive: false,
    form: [
      { prop: 'name', label: '小区名称', required: true },
      { prop: 'managed_area', label: '管理面积（㎡）', type: 'number', required: true },
      { prop: 'address', label: '地址', type: 'textarea' },
      { prop: 'contact_name', label: '服务中心名称' },
      { prop: 'status', label: '状态', type: 'select', required: true, options: statusOptions.slice(0, 2) },
    ],
    defaults: { status: 'ACTIVE', managed_area: '0' },
    description: '当前为单项目独立数据库：可维护本项目名称和档案，但不能新增或停用第二个项目。',
  }),
  '/archives/grids': base('grids', 'property', [
    { prop: 'code', label: '网格编码', width: 140, sortable: true, sortKey: 'code' },
    { prop: 'name', label: '网格名称', minWidth: 210, sortable: true, sortKey: 'name' },
    { prop: 'parent_id', label: '上级网格 ID', minWidth: 220 },
    { prop: 'manager_user_id', label: '网格员 ID', minWidth: 220 },
    { prop: 'sort_order', label: '顺序', width: 90, sortable: true, sortKey: 'sortOrder' }, state(), updated,
  ], {
    writePermission: 'property:write',
    form: [
      { prop: 'code', label: '网格编码', required: true }, { prop: 'name', label: '网格名称', required: true },
      { prop: 'parent_id', label: '上级网格 ID' }, { prop: 'manager_user_id', label: '网格员 ID' },
      { prop: 'sort_order', label: '显示顺序', type: 'number' },
      { prop: 'status', label: '状态', type: 'select', required: true, options: statusOptions.slice(0, 2) },
    ],
    defaults: { sort_order: 0, status: 'ACTIVE' },
  }),
  '/archives/buildings': base('buildings', 'property', [
    { prop: 'code', label: '楼栋编码', width: 130, sortable: true, sortKey: 'code' },
    { prop: 'name', label: '楼栋名称', minWidth: 200, sortable: true, sortKey: 'name' },
    { prop: 'building_type', label: '楼栋类型', width: 130 },
    { prop: 'floor_count', label: '楼层数', width: 100, sortable: true, sortKey: 'floorCount' }, state(), updated,
  ], {
    writePermission: 'property:write',
    form: [
      { prop: 'code', label: '楼栋编码', required: true }, { prop: 'name', label: '楼栋名称', required: true },
      { prop: 'grid_id', label: '所属网格 ID' },
      { prop: 'building_type', label: '楼栋类型', type: 'select', required: true, options: [
        { label: '住宅', value: 'RESIDENTIAL' }, { label: '商业', value: 'COMMERCIAL' },
      ] },
      { prop: 'floor_count', label: '楼层数', type: 'number' },
      { prop: 'status', label: '状态', type: 'select', required: true, options: statusOptions.slice(0, 2) },
    ], defaults: { status: 'ACTIVE', building_type: 'RESIDENTIAL' },
  }),
  '/archives/units': base('units', 'property', [
    { prop: 'code', label: '单元编码', width: 140, sortable: true, sortKey: 'code' },
    { prop: 'name', label: '单元名称', minWidth: 220, sortable: true, sortKey: 'name' }, state(), updated,
  ], {
    writePermission: 'property:write',
    form: [
      { prop: 'building_id', label: '所属楼栋 ID', required: true },
      { prop: 'code', label: '单元编码', required: true }, { prop: 'name', label: '单元名称', required: true },
      { prop: 'status', label: '状态', type: 'select', required: true, options: statusOptions.slice(0, 2) },
    ],
    defaults: { status: 'ACTIVE' },
    description: '单元写入会校验楼栋与当前项目一致；仍被有效房产引用时不能停用。',
  }),
  '/archives/rooms': base('assets', 'property', [
    { prop: 'code', label: '房屋编码', width: 140, sortable: true, sortKey: 'code' },
    { prop: 'display_name', label: '房屋名称', minWidth: 220, sortable: true, sortKey: 'displayName' },
    { prop: 'floor_no', label: '楼层', width: 90 },
    { prop: 'building_area', label: '建筑面积', width: 120, sortable: true, sortKey: 'buildingArea' },
    { prop: 'occupancy_status', label: '入住状态', width: 120 }, state('operation_status'), updated,
  ], {
    category: 'ROOM', writePermission: 'property:write',
    form: [
      { prop: 'code', label: '房屋编码', required: true }, { prop: 'display_name', label: '房屋名称', required: true },
      { prop: 'floor_no', label: '楼层' }, { prop: 'building_area', label: '建筑面积', type: 'number', required: true },
      { prop: 'usable_area', label: '使用面积', type: 'number', required: true },
      { prop: 'occupancy_status', label: '入住状态', type: 'select', options: [
        { label: '已入住', value: 'OCCUPIED' }, { label: '空置', value: 'VACANT' },
      ], required: true },
      { prop: 'operation_status', label: '运营状态', type: 'select', options: [
        { label: '正常', value: 'NORMAL' }, { label: '停用', value: 'INACTIVE' },
      ], required: true },
    ], defaults: { asset_type: 'ROOM', occupancy_status: 'VACANT', operation_status: 'NORMAL', enabled: true },
  }),
  '/archives/customers': base('customers', 'property', [
    { prop: 'display_name', label: '客户名称', minWidth: 210, sortable: true, sortKey: 'displayName' },
    { prop: 'customer_type', label: '客户类型', width: 120, sortable: true, sortKey: 'customerType' },
    { prop: 'customer_class', label: '客户分类', width: 120 },
    { prop: 'mobile_masked', label: '脱敏联系方式', width: 160 }, state(), updated,
  ], {
    writePermission: 'property:write',
    form: [
      { prop: 'display_name', label: '客户名称', required: true },
      { prop: 'customer_type', label: '客户类型', type: 'select', required: true, options: [
        { label: '个人', value: 'PERSON' }, { label: '机构', value: 'ORGANIZATION' },
      ] },
      { prop: 'customer_class', label: '客户分类', type: 'select', options: [
        { label: '业主', value: 'OWNER' }, { label: '租户', value: 'TENANT' },
      ] },
      { prop: 'mobile_masked', label: '脱敏联系方式' },
      { prop: 'status', label: '状态', type: 'select', required: true, options: statusOptions.slice(0, 2) },
    ], defaults: { customer_type: 'PERSON', customer_class: 'OWNER', status: 'ACTIVE' },
  }),
  '/archives/customer-assets': base('customer-asset-relations', 'property', [
    { prop: 'customer_id', label: '客户 ID', minWidth: 230 }, { prop: 'asset_id', label: '资产 ID', minWidth: 230 },
    { prop: 'relation_type', label: '关系类型', width: 120 }, { prop: 'start_date', label: '生效日期', width: 120, sortable: true, sortKey: 'startDate' }, state(), updated,
  ]),
  '/archives/parking-spaces': base('assets', 'property', [
    { prop: 'code', label: '车位编码', width: 140, sortable: true, sortKey: 'code' },
    { prop: 'display_name', label: '车位名称', minWidth: 220, sortable: true, sortKey: 'displayName' },
    { prop: 'floor_no', label: '区域/楼层', width: 120 }, { prop: 'occupancy_status', label: '使用状态', width: 120 },
    state('operation_status'), updated,
  ], {
    category: 'PARKING', writePermission: 'property:write',
    form: [
      { prop: 'code', label: '车位编码', required: true }, { prop: 'display_name', label: '车位名称', required: true },
      { prop: 'floor_no', label: '区域/楼层' },
      { prop: 'occupancy_status', label: '使用状态', type: 'select', required: true, options: [
        { label: '已使用', value: 'OCCUPIED' }, { label: '空闲', value: 'VACANT' },
      ] },
      { prop: 'operation_status', label: '运营状态', type: 'select', required: true, options: [
        { label: '正常', value: 'NORMAL' }, { label: '停用', value: 'INACTIVE' },
      ] },
    ], defaults: { asset_type: 'PARKING', building_area: '12.50', usable_area: '12.50', occupancy_status: 'VACANT', operation_status: 'NORMAL', enabled: true },
  }),
  '/archives/vehicles': base('vehicles', 'property', [
    { prop: 'plate_no_masked', label: '脱敏车牌', minWidth: 180, sortable: true, sortKey: 'plate' },
    { prop: 'vehicle_type', label: '车辆类型', width: 130 }, { prop: 'color', label: '颜色', width: 110 }, state(), updated,
  ], {
    writePermission: 'property:write',
    form: [
      { prop: 'plate_no_masked', label: '脱敏车牌', required: true },
      { prop: 'vehicle_type', label: '车辆类型', type: 'select', required: true, options: [
        { label: '燃油车', value: 'CAR' }, { label: '新能源车', value: 'NEW_ENERGY' },
      ] },
      { prop: 'color', label: '颜色' }, { prop: 'status', label: '状态', type: 'select', required: true, options: statusOptions.slice(0, 2) },
    ], defaults: { vehicle_type: 'CAR', status: 'ACTIVE' },
  }),
  '/archives/meters': base('meters', 'meter', [
    { prop: 'meter_no', label: '仪表编号', minWidth: 180, sortable: true, sortKey: 'meterNo' },
    { prop: 'meter_type', label: '仪表类型', width: 120, sortable: true, sortKey: 'meterType' },
    { prop: 'meter_class', label: '仪表级别', width: 110 }, { prop: 'multiplier', label: '倍率', width: 100 }, state(), updated,
  ], {
    writePermission: 'meter:write',
    form: [
      { prop: 'meter_no', label: '仪表编号', required: true },
      { prop: 'meter_type', label: '仪表类型', type: 'select', required: true, options: [
        { label: '水表', value: 'WATER' }, { label: '电表', value: 'ELECTRICITY' }, { label: '燃气表', value: 'GAS' },
      ] },
      { prop: 'meter_class', label: '仪表级别', type: 'select', required: true, options: [
        { label: '总表', value: 'MASTER' }, { label: '分表', value: 'SUB' },
      ] },
      { prop: 'multiplier', label: '倍率', type: 'number', required: true },
      { prop: 'loss_rate', label: '损耗率', type: 'number' }, { prop: 'correction', label: '修正值', type: 'number' },
      { prop: 'status', label: '状态', type: 'select', required: true, options: statusOptions.slice(0, 2) },
    ], defaults: { meter_type: 'WATER', meter_class: 'SUB', multiplier: '1', loss_rate: '0', correction: '0', status: 'ACTIVE' },
  }),
  '/fees/definitions': base('fee-definitions', 'fee', [
    { prop: 'code', label: '费用编码', width: 140, sortable: true, sortKey: 'code' },
    { prop: 'name', label: '费用名称', minWidth: 220, sortable: true, sortKey: 'name' },
    { prop: 'fee_type', label: '费用类型', width: 130 }, { prop: 'fee_class', label: '计费类别', width: 130 },
    { prop: 'unit_code', label: '计量单位', width: 130 }, { prop: 'enabled', label: '启用', width: 90 }, updated,
  ], {
    writePermission: 'fee:write',
    form: [
      { prop: 'code', label: '费用编码', required: true }, { prop: 'name', label: '费用名称', required: true },
      { prop: 'fee_type', label: '费用类型', required: true }, { prop: 'fee_class', label: '计费类别', required: true },
      { prop: 'unit_code', label: '计量单位', required: true }, { prop: 'decimal_scale', label: '金额精度', type: 'number', required: true },
      { prop: 'late_fee_enabled', label: '启用滞纳金', type: 'switch' }, { prop: 'enabled', label: '启用', type: 'switch' },
    ], defaults: { decimal_scale: 2, late_fee_enabled: false, enabled: true },
  }),
  '/fees/standards': base('fee-standards', 'fee', [
    { prop: 'code', label: '标准编码', width: 140, sortable: true, sortKey: 'code' },
    { prop: 'name', label: '标准名称', minWidth: 220, sortable: true, sortKey: 'name' },
    { prop: 'asset_type', label: '资产类型', width: 120 }, { prop: 'billing_cycle', label: '计费周期', width: 120 }, state(), updated,
  ]),
  '/fees/allocations': base('fee-allocations', 'fee', [
    { prop: 'fee_standard_id', label: '费用标准 ID', minWidth: 230 }, { prop: 'asset_id', label: '资产 ID', minWidth: 230 },
    { prop: 'coefficient', label: '系数', width: 100 }, { prop: 'effective_from', label: '生效日期', width: 120, sortable: true, sortKey: 'effectiveFrom' }, state(), updated,
  ]),
  '/fees/receivables': base('receivable-jobs', 'fee', [
    { prop: 'billing_period', label: '账期', width: 120 }, { prop: 'request_key', label: '请求键', minWidth: 220 },
    { prop: 'preview', label: '预览', width: 90 }, { prop: 'generated_count', label: '生成数', width: 100 },
    { prop: 'error_count', label: '错误数', width: 100 }, state(), { prop: 'created_at', label: '创建时间', width: 180 },
  ]),
  '/cashier': base('bills', 'cashier', [
    { prop: 'bill_no', label: '账单号', minWidth: 210 }, { prop: 'billing_period', label: '账期', width: 100 },
    { prop: 'total_amount', label: '应收金额', width: 120 }, { prop: 'outstanding_amount', label: '待收金额', width: 120 }, state(),
  ], { description: '收银写操作使用专用业务接口；当前列表展示待缴账单上下文。' }),
  '/finance/bills': base('bills', 'cashier', [
    { prop: 'bill_no', label: '账单号', minWidth: 210 }, { prop: 'billing_period', label: '账期', width: 100 },
    { prop: 'total_amount', label: '应收金额', width: 120 }, { prop: 'paid_amount', label: '已收金额', width: 120 },
    { prop: 'outstanding_amount', label: '待收金额', width: 120 }, { prop: 'due_date', label: '到期日', width: 120 }, state(), updated,
  ]),
  '/finance/arrears': base('bills', 'cashier', [
    { prop: 'bill_no', label: '欠费账单号', minWidth: 210 }, { prop: 'billing_period', label: '账期', width: 100 },
    { prop: 'outstanding_amount', label: '欠费金额', width: 130 }, { prop: 'due_date', label: '到期日', width: 120 }, state(),
  ]),
  '/finance/prepayments': base('prepayment-accounts', 'cashier', [
    { prop: 'customer_id', label: '客户 ID', minWidth: 230 }, { prop: 'balance', label: '可用余额', width: 130 },
    { prop: 'frozen_balance', label: '冻结余额', width: 130 }, updated,
  ]),
  '/finance/deposits': base('deposits', 'cashier', [
    { prop: 'customer_id', label: '客户 ID', minWidth: 220 }, { prop: 'deposit_type', label: '押金类型', width: 130 },
    { prop: 'balance', label: '余额', width: 130 }, state(), updated,
  ]),
  '/finance/payments': base('payment-transactions', 'cashier', [
    { prop: 'transaction_no', label: '交易号', minWidth: 220 }, { prop: 'transaction_type', label: '交易类型', width: 130 },
    { prop: 'adapter_code', label: '通道', width: 120 }, { prop: 'amount', label: '金额', width: 120, sortable: true, sortKey: 'amount' },
    state(), { prop: 'occurred_at', label: '发生时间', width: 180, sortable: true, sortKey: 'occurredAt' },
  ]),
  '/finance/receipts': base('receipts', 'cashier', [
    { prop: 'receipt_no', label: '收据号', minWidth: 210 }, { prop: 'template_version', label: '模板版本', width: 130 },
    state(), { prop: 'issued_at', label: '开具时间', width: 180 },
  ]),
  '/metering/batches': base('meter-reading-batches', 'meter', [
    { prop: 'batch_no', label: '批次号', minWidth: 200 }, { prop: 'reading_period', label: '抄表周期', width: 120 },
    { prop: 'source_type', label: '来源', width: 120 }, state(), updated,
  ]),
  '/metering/readings': base('meter-readings', 'meter', [
    { prop: 'meter_id', label: '仪表 ID', minWidth: 220 }, { prop: 'previous_reading', label: '上期读数', width: 120 },
    { prop: 'current_reading', label: '本期读数', width: 120 }, { prop: 'billable_usage', label: '计费用量', width: 120, sortable: true, sortKey: 'usage' },
    state(), { prop: 'reading_at', label: '抄表时间', width: 180, sortable: true, sortKey: 'readingAt' },
  ]),
  '/metering/share-rules': base('meter-share-rules', 'meter', [
    { prop: 'code', label: '规则编码', width: 150 }, { prop: 'name', label: '规则名称', minWidth: 220 },
    { prop: 'strategy_code', label: '策略', width: 140 }, state(), updated,
  ]),
  '/metering/share-preview': base('meter-share-rules', 'meter', [
    { prop: 'code', label: '规则编码', width: 150 }, { prop: 'name', label: '待试算规则', minWidth: 240 },
    { prop: 'strategy_code', label: '规则策略', width: 160 }, state(),
  ], { description: '公摊公式为可替换策略，正式使用前必须确认项目业务口径。' }),
  '/metering/replacements': base('meter-replacements', 'meter', [
    { prop: 'old_meter_id', label: '旧表 ID', minWidth: 220 }, { prop: 'new_meter_id', label: '新表 ID', minWidth: 220 },
    { prop: 'old_final_reading', label: '旧表末次读数', width: 140 }, { prop: 'new_initial_reading', label: '新表初始读数', width: 140 },
    { prop: 'replaced_at', label: '换表时间', width: 180, sortable: true, sortKey: 'replacedAt' },
  ]),
  '/metering/charges': base('receivable-jobs', 'meter', [
    { prop: 'billing_period', label: '计量账期', width: 120 }, { prop: 'request_key', label: '生成请求键', minWidth: 220 },
    { prop: 'generated_count', label: '生成数', width: 100 }, { prop: 'error_count', label: '错误数', width: 100 }, state(),
  ]),
  '/system/users': { resource: 'users', readPermission: 'system:audit', columns: [
    { prop: 'username', label: '账号', minWidth: 180, sortable: true, sortKey: 'username' },
    { prop: 'display_name', label: '显示名称', minWidth: 200, sortable: true, sortKey: 'displayName' },
    { prop: 'enabled', label: '启用', width: 100 }, updated,
  ] },
  '/system/dictionaries': { resource: 'dictionaries', readPermission: 'dashboard:read', columns: [
    { prop: 'dictionary_type', label: '字典类型', minWidth: 180 }, { prop: 'code', label: '代码', minWidth: 170 },
    { prop: 'display_name', label: '显示名称', minWidth: 180, sortable: true, sortKey: 'displayName' },
    { prop: 'sort_order', label: '顺序', width: 90, sortable: true, sortKey: 'sortOrder' }, { prop: 'enabled', label: '启用', width: 90 },
  ] },
  '/system/audits': { resource: 'audit-events', readPermission: 'system:audit', columns: [
    { prop: 'action_code', label: '动作', minWidth: 190 }, { prop: 'resource_type', label: '资源', width: 150 },
    { prop: 'resource_id', label: '资源 ID', minWidth: 210 }, { prop: 'result_status', label: '结果', width: 100 },
    { prop: 'occurred_at', label: '发生时间', width: 180, sortable: true, sortKey: 'occurredAt' },
  ] },
  '/system/adapters': { resource: 'dictionaries', readPermission: 'dashboard:read', columns: [
    { prop: 'dictionary_type', label: '配置类型', minWidth: 180 }, { prop: 'code', label: '通道代码', minWidth: 180 },
    { prop: 'display_name', label: '状态说明', minWidth: 260 },
  ], description: '支付、发票和 IoT 默认均为模拟通道；Java110 适配器默认禁用。' },
}

export function schemaFor(path: string): PageSchema | undefined {
  return pageSchemas[path]
}
