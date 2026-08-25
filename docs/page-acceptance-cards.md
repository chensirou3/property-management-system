# PMS3 49 页验收卡

> 本文档由 `apps/admin-web/src/config/page-catalog.json` 自动生成，请勿手工修改。目录版本：2026-08-25。

## 使用规则

- 这 49 张卡是固定范围，不把辅助管理页误算为目标页面，也不以菜单出现代替完成。
- 每页必须具备稳定路由、明确查询/列模型、按钮权限和八类可见状态。
- “已登记”仅表示 G2 元数据完备；只有结构、功能、数据和外部边界证据齐全后，才可在最终追踪矩阵中标为完成。
- 复杂工作台、状态流转、报表、迁移和外部适配页面必须使用对应专用实现，不得退化为万能 CRUD。
- 生成命令：`npm run pages:acceptance`；漂移检查：`npm run pages:acceptance:check`。

## 覆盖摘要

| 项目 | 数量 |
|---|---:|
| 总页面 | 49 |
| 波次 A | 17 |
| 波次 B | 20 |
| 波次 C | 10 |
| 波次 D | 2 |
| 每页必备状态 | 8 |

## 证据源索引

| 证据 | 位置 | 用途 |
|---|---|---|
| 页面与接口证据附录 | `../PMS3目标系统调查/PMS3页面与接口证据附录.md` | 49 页目标路由、静态字段、按钮、表格列与接口线索 |
| 只读审计快照 | `../PMS3目标系统调查/scans/2026-08-24/pms3_audit_snapshot-2026-08-24.json` | 2026-08-24 授权只读扫描的机器记录 |
| 脱敏数据统计 | `../PMS3目标系统调查/scans/2026-08-24/优山美地基础数据统计（脱敏）-2026-08-24.json` | 可授权使用的数据数量、字段与关系基线 |
| 目标评估报告 | `../PMS3目标系统调查/PMS3目标系统功能与UI复刻评估报告.md` | UI、功能、数据和风险判断 |
| 当前视觉基线 | `apps/admin-web/e2e/visual-baselines.spec.ts-snapshots/` | 当前登录与看板三视口回归基线 |

原始调查资料位于工程同级目录，属于只读授权证据，不复制账号、密码、Cookie、令牌或业务记录值到本工程。

## 目标与重构路由索引

| 序号 | 页面 | 目标只读路由 | 重构路由 | 证据索引 |
|---:|---|---|---|---|
| 1 | 看板 | `/main/487` | `/dashboard` | PMS3页面与接口证据附录#01 |
| 2 | 组织管理 | `/system/sysDept/73` | `/enterprise/organizations` | PMS3页面与接口证据附录#02 |
| 3 | 角色管理 | `/sys/role/7` | `/enterprise/roles` | PMS3页面与接口证据附录#03 |
| 4 | 人员管理 | `/sys/user/6` | `/enterprise/employees` | PMS3页面与接口证据附录#04 |
| 5 | 小区信息 | `/area/area/107` | `/archives/communities` | PMS3页面与接口证据附录#05 |
| 6 | 房产信息 | `/room/room/112` | `/archives/rooms` | PMS3页面与接口证据附录#06 |
| 7 | 客户信息 | `/users/users/118` | `/archives/customers` | PMS3页面与接口证据附录#07 |
| 8 | 费用定义 | `/infee/setfee/136` | `/fees/definitions` | PMS3页面与接口证据附录#08 |
| 9 | 费用分配 | `/infee/feeset/141` | `/fees/allocations` | PMS3页面与接口证据附录#09 |
| 10 | 生成应收 | `/infee/receivable/163` | `/fees/receivables` | PMS3页面与接口证据附录#10 |
| 11 | 生成应收—临时 | `/infee/receivableTemporary/297` | `/fees/temporary-receivables` | PMS3页面与接口证据附录#11 |
| 12 | 收银台 | `/infee/cashier/186` | `/cashier` | PMS3页面与接口证据附录#12 |
| 13 | 欠费明细表 | `/dataquery/arrearage/269` | `/finance/arrears` | PMS3页面与接口证据附录#13 |
| 14 | 应收管理 | `/dataquery/recemanagement/264` | `/finance/bills` | PMS3页面与接口证据附录#14 |
| 15 | 日结明细表 | `/dataquery/dailyStatement/400` | `/reports/daily-settlement-details` | PMS3页面与接口证据附录#15 |
| 16 | 调账记录 | `/dataquery/tiaozhangjilu/661` | `/finance/adjustments` | PMS3页面与接口证据附录#16 |
| 17 | 数据迁移 | `/dataTransfer/dataTransfer/263` | `/system/migrations` | PMS3页面与接口证据附录#17 |
| 18 | 企业管理 | `/company/company/103` | `/enterprise/enterprises` | PMS3页面与接口证据附录#18 |
| 19 | 岗位管理 | `/system/post/210` | `/enterprise/positions` | PMS3页面与接口证据附录#19 |
| 20 | 网格管理 | `/grid/grid/546` | `/archives/grids` | PMS3页面与接口证据附录#20 |
| 21 | 车位信息 | `/park/parkspace/134` | `/archives/parking-spaces` | PMS3页面与接口证据附录#21 |
| 22 | 仪表管理 | `/meter/meter/128` | `/archives/meters` | PMS3页面与接口证据附录#22 |
| 23 | 票据管理 | `/infee/bill/151` | `/finance/instruments` | PMS3页面与接口证据附录#23 |
| 24 | 抄表 | `/infee/meterread/156` | `/metering/readings` | PMS3页面与接口证据附录#24 |
| 25 | 折扣管理 | `/infee/discount/193` | `/fees/discounts` | PMS3页面与接口证据附录#25 |
| 26 | 批量冲预收款 | `/infee/plcysk/446` | `/finance/prepayment-batch-offsets` | PMS3页面与接口证据附录#26 |
| 27 | 交易汇总 | `/dataquery/summary/268` | `/reports/transaction-summary` | PMS3页面与接口证据附录#27 |
| 28 | 交易明细 | `/dataquery/jiaoyimingxi/290` | `/reports/transaction-details` | PMS3页面与接口证据附录#28 |
| 29 | 交易记录 | `/dataquery/transactiondetail/228` | `/finance/payments` | PMS3页面与接口证据附录#29 |
| 30 | 收缴率报表 | `/dataquery/shoujiaolv/254` | `/reports/collection-rate` | PMS3页面与接口证据附录#30 |
| 31 | 清欠率 | `/dataquery/qingqianlv/270` | `/reports/arrears-clearance-rate` | PMS3页面与接口证据附录#31 |
| 32 | 收清欠汇总表 | `/dataquery/acquittanceCollect/278` | `/reports/collection-clearance-summary` | PMS3页面与接口证据附录#32 |
| 33 | 收费明细报表 | `/dataquery/infeedetail/229` | `/reports/charge-details` | PMS3页面与接口证据附录#33 |
| 34 | 预收款报表 | `/dataquery/yushoukuanbaobiao/318` | `/reports/prepayments` | PMS3页面与接口证据附录#34 |
| 35 | 费用情况表 | `/dataquery/feiyongqingkuang/317` | `/reports/fee-status` | PMS3页面与接口证据附录#35 |
| 36 | 押金记录表 | `/dataquery/depositRecord/401` | `/finance/deposits` | PMS3页面与接口证据附录#36 |
| 37 | 数据字典 | `/common/dict/78` | `/system/dictionaries` | PMS3页面与接口证据附录#37 |
| 38 | 看板配置 | `/kanban/options/488` | `/dashboard/configuration` | PMS3页面与接口证据附录#38 |
| 39 | 第三方参数设置 | `/infee/feeThirdpart/198` | `/system/third-party-settings` | PMS3页面与接口证据附录#39 |
| 40 | 换发票 | `/infee/invoice/365` | `/finance/invoice-replacements` | PMS3页面与接口证据附录#40 |
| 41 | 批量打印收据 | `/dataquery/transactionBatch/512` | `/finance/receipt-batch-print` | PMS3页面与接口证据附录#41 |
| 42 | 账单通知 | `/dataquery/billnotice/267` | `/finance/bill-notifications` | PMS3页面与接口证据附录#42 |
| 43 | 综合查询表 | `/dataquery/multimeter/295` | `/reports/comprehensive-query` | PMS3页面与接口证据附录#43 |
| 44 | 折扣明细表 | `/dataquery/discountDetail/273` | `/reports/discount-details` | PMS3页面与接口证据附录#44 |
| 45 | 过户查询 | `/dataquery/transfer/294` | `/reports/ownership-transfers` | PMS3页面与接口证据附录#45 |
| 46 | 提醒明细 | `/dataquery/remind/296` | `/reports/reminders` | PMS3页面与接口证据附录#46 |
| 47 | 发票统计表 | `/dataquery/fapiaotongji/389` | `/reports/invoice-statistics` | PMS3页面与接口证据附录#47 |
| 48 | 银行信托 | `/dataquery/banktrust/308` | `/finance/bank-trust` | PMS3页面与接口证据附录#48 |
| 49 | 访客记录 | `/visitorChargeOff/visitorLog/668` | `/security/visitor-records` | PMS3页面与接口证据附录#49 |

## 01. 看板

| 项目 | 定义 |
|---|---|
| 路由 | `/dashboard` |
| 波次 / 领域 | A / `dashboard` |
| 页面实现 | `specialized` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `dashboard:read`；导出 `dashboard:export` |
| 主要操作 | `refresh`、`configure`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#01 |
| 目标路由 | `/main/487` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `projectId` | 当前项目 | 项目选择 |
| `dateRange` | 统计周期 | 日期范围 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `metric` | 指标 | 文本 |
| `currentValue` | 本期值 | 数值 |
| `previousValue` | 上期值 | 数值 |
| `trend` | 趋势 | 状态 |

### 验收记录

- 业务假设：指标口径在 G7 依据业务金标准复核。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 02. 组织管理

| 项目 | 定义 |
|---|---|
| 路由 | `/enterprise/organizations` |
| 波次 / 领域 | A / `iam` |
| 页面实现 | `tree-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `iam:read`；写操作 `iam:write`；导出 `iam:export` |
| 主要操作 | `create`、`edit`、`disable`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#02 |
| 目标路由 | `/system/sysDept/73` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 组织名称/编码 | 文本 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 组织编码 | 文本 |
| `name` | 组织名称 | 文本 |
| `parentName` | 上级组织 | 文本 |
| `managerName` | 负责人 | 文本 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：组织层级以当前企业为根。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 03. 角色管理

| 项目 | 定义 |
|---|---|
| 路由 | `/enterprise/roles` |
| 波次 / 领域 | A / `iam` |
| 页面实现 | `permission-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `iam:read`；写操作 `iam:write`；导出 `iam:export` |
| 主要操作 | `create`、`edit`、`assign-permissions`、`assign-users`、`disable`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#03 |
| 目标路由 | `/sys/role/7` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 角色名称/编码 | 文本 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 角色编码 | 文本 |
| `name` | 角色名称 | 文本 |
| `userCount` | 账号数 | 数值 |
| `projectScope` | 项目范围 | 文本 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：按钮权限与数据范围分开配置。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 04. 人员管理

| 项目 | 定义 |
|---|---|
| 路由 | `/enterprise/employees` |
| 波次 / 领域 | A / `iam` |
| 页面实现 | `master-detail` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `iam:read`；写操作 `iam:write`；导出 `iam:export` |
| 主要操作 | `create`、`edit`、`bind-account`、`assign-position`、`disable`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#04 |
| 目标路由 | `/sys/user/6` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 姓名/工号/手机 | 文本 |
| `organizationId` | 所属组织 | 树选择 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `employeeNo` | 工号 | 文本 |
| `displayName` | 姓名 | 文本 |
| `organizationName` | 所属组织 | 文本 |
| `positionName` | 岗位 | 文本 |
| `mobileMasked` | 手机号 | 脱敏文本 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：个人信息默认脱敏，导出单独审计。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 05. 小区信息

| 项目 | 定义 |
|---|---|
| 路由 | `/archives/communities` |
| 波次 / 领域 | A / `archives` |
| 页面实现 | `catalog` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `property:read`；写操作 `property:write`；导出 `property:export` |
| 主要操作 | `create`、`edit`、`disable`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#05 |
| 目标路由 | `/area/area/107` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 小区名称/编码 | 文本 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 小区编码 | 文本 |
| `name` | 小区名称 | 文本 |
| `managedArea` | 管理面积 | 面积 |
| `address` | 地址 | 文本 |
| `serviceCenter` | 服务中心 | 文本 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：管理面积以平方米保存，展示保留两位。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 06. 房产信息

| 项目 | 定义 |
|---|---|
| 路由 | `/archives/rooms` |
| 波次 / 领域 | A / `archives` |
| 页面实现 | `asset-tree-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `property:read`；写操作 `property:write`；导入 `property:import`；导出 `property:export` |
| 主要操作 | `create`、`edit`、`bind-customer`、`transfer`、`import`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#06 |
| 目标路由 | `/room/room/112` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 房号/客户 | 文本 |
| `buildingId` | 楼栋 | 树选择 |
| `occupancyStatus` | 入住状态 | 下拉选择 |
| `operationStatus` | 运营状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 房屋编码 | 文本 |
| `displayName` | 房屋名称 | 文本 |
| `buildingName` | 楼栋 | 文本 |
| `floorNo` | 楼层 | 数值 |
| `buildingArea` | 建筑面积 | 面积 |
| `customerName` | 当前客户 | 脱敏文本 |
| `operationStatus` | 状态 | 状态 |

### 验收记录

- 业务假设：当前关系与历史关系分层展示。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 07. 客户信息

| 项目 | 定义 |
|---|---|
| 路由 | `/archives/customers` |
| 波次 / 领域 | A / `archives` |
| 页面实现 | `relationship-timeline` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `property:read`；写操作 `property:write`；导入 `property:import`；导出 `property:export-sensitive` |
| 主要操作 | `create`、`edit`、`bind-asset`、`view-history`、`import`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#07 |
| 目标路由 | `/users/users/118` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 客户名称/证件/手机 | 文本 |
| `customerType` | 客户类型 | 下拉选择 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `customerNo` | 客户编号 | 文本 |
| `displayName` | 客户名称 | 文本 |
| `customerType` | 客户类型 | 状态 |
| `mobileMasked` | 手机号 | 脱敏文本 |
| `assetCount` | 关联资产数 | 数值 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：证件号、手机号默认脱敏。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 08. 费用定义

| 项目 | 定义 |
|---|---|
| 路由 | `/fees/definitions` |
| 波次 / 领域 | A / `fees` |
| 页面实现 | `versioned-config` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `fee:read`；写操作 `fee:write`；导出 `fee:export` |
| 主要操作 | `create-definition`、`edit-definition`、`create-standard`、`add-version`、`disable-standard` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#08 |
| 目标路由 | `/infee/setfee/136` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 费用名称/编码 | 文本 |
| `feeType` | 费用类型 | 下拉选择 |
| `enabled` | 启用状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 费用编码 | 文本 |
| `displayName` | 费用名称 | 文本 |
| `feeType` | 费用类型 | 状态 |
| `subjectCode` | 会计科目 | 文本 |
| `taxRate` | 税率 | 数值 |
| `decimalScale` | 精度 | 数值 |
| `roundingMode` | 舍入方式 | 状态 |
| `enabled` | 状态 | 状态 |

### 验收记录

- 业务假设：财税字段和舍入由定义治理；标准变更追加生效版本，历史账单保存原快照。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 09. 费用分配

| 项目 | 定义 |
|---|---|
| 路由 | `/fees/allocations` |
| 波次 / 领域 | A / `fees` |
| 页面实现 | `allocation-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `fee:read`；写操作 `fee:write`；导入 `fee:import`；导出 `fee:export` |
| 主要操作 | `preview`、`assign`、`replay`、`cancel`、`refresh` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#09 |
| 目标路由 | `/infee/feeset/141` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `standardId` | 费用标准 | 下拉选择 |
| `targetType` | 目标类型 | 下拉选择 |
| `keyword` | 对象编码/名称 | 文本 |
| `effectiveDate` | 生效日期 | 日期 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `standardName` | 费用标准 | 文本 |
| `targetType` | 目标类型 | 状态 |
| `targetCode` | 对象编码 | 文本 |
| `targetName` | 对象名称 | 文本 |
| `coefficient` | 系数 | 数值 |
| `effectiveFrom` | 生效日期 | 日期 |
| `effectiveTo` | 失效日期 | 日期 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：批量分配必须先预览命中和冲突；取消截断有效期，不删除历史。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 10. 生成应收

| 项目 | 定义 |
|---|---|
| 路由 | `/fees/receivables` |
| 波次 / 领域 | A / `fees` |
| 页面实现 | `receivable-workflow` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `fee:read`；写操作 `fee:write`；导出 `fee:export` |
| 主要操作 | `preview`、`enqueue`、`poll-task`、`view-items`、`view-errors`、`reconcile` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#10 |
| 目标路由 | `/infee/receivable/163` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billingPeriod` | 账期 | 月份 |
| `keyword` | 房屋编码/名称 | 文本 |
| `assetIds` | 计费资产 | multi-select |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `feeName` | 费用项目 | 文本 |
| `standardVersion` | 标准版本 | 文本 |
| `calculationBasis` | 计算基础 | 状态 |
| `roundingMode` | 舍入 | 状态 |
| `amount` | 应收金额 | 金额 |
| `validationStatus` | 校验结果 | 状态 |

### 验收记录

- 业务假设：生成使用请求键和请求哈希，异步落账并保存配置校验值与三项对账。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 11. 生成应收—临时

| 项目 | 定义 |
|---|---|
| 路由 | `/fees/temporary-receivables` |
| 波次 / 领域 | A / `fees` |
| 页面实现 | `temporary-receivable-workflow` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `fee:read`；写操作 `fee:write`；导入 `fee:import`；导出 `fee:export` |
| 主要操作 | `add-line`、`remove-line`、`validate-preview`、`enqueue`、`view-task`、`reconcile` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#11 |
| 目标路由 | `/infee/receivableTemporary/297` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `assetId` | 房产 | 资产选择 |
| `customerId` | 客户 | 客户选择 |
| `chargeDate` | 计费日期 | 日期 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `feeDefinition` | 临时费用定义 | 文本 |
| `description` | 临时费用说明 | 文本 |
| `quantity` | 数量 | 数值 |
| `unitPrice` | 单价 | 金额 |
| `amount` | 应收金额 | 金额 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：临时应收只使用显式允许的定义，不复用周期分配规则；任务仍使用幂等和不可变快照。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 12. 收银台

| 项目 | 定义 |
|---|---|
| 路由 | `/cashier` |
| 波次 / 领域 | A / `cashier` |
| 页面实现 | `cashier-workflow` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `cashier:read`；写操作 `cashier:write`；打印 `cashier:print` |
| 主要操作 | `select-bills`、`apply-prepayment`、`collect`、`reverse`、`print-receipt` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#12 |
| 目标路由 | `/infee/cashier/186` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 房产/客户/手机号 | 文本 |
| `chargeScope` | 收费范围 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billNo` | 账单号 | 文本 |
| `feeName` | 费用项目 | 文本 |
| `billingPeriod` | 账期 | 月份 |
| `receivableAmount` | 应收 | 金额 |
| `paidAmount` | 已收 | 金额 |
| `outstandingAmount` | 待收 | 金额 |

### 验收记录

- 业务假设：线上支付在无生产商户时使用可替换模拟通道。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 13. 欠费明细表

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/arrears` |
| 波次 / 领域 | A / `finance` |
| 页面实现 | `report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；导出 `finance:export`；打印 `finance:print` |
| 主要操作 | `query`、`export`、`print`、`view-bill` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#13 |
| 目标路由 | `/dataquery/arrearage/269` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billingPeriodRange` | 账期范围 | 月份范围 |
| `communityId` | 小区 | 下拉选择 |
| `feeDefinitionId` | 费用项目 | 下拉选择 |
| `keyword` | 房产/客户 | 文本 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `feeName` | 费用项目 | 文本 |
| `billingPeriod` | 账期 | 月份 |
| `receivableAmount` | 应收 | 金额 |
| `arrearsAmount` | 欠费 | 金额 |
| `arrearsDays` | 欠费天数 | 数值 |

### 验收记录

- 业务假设：欠费不含已全额冲正账单。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 14. 应收管理

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/bills` |
| 波次 / 领域 | A / `finance` |
| 页面实现 | `bill-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；写操作 `finance:write`；导出 `finance:export` |
| 主要操作 | `view`、`adjust`、`void`、`batch-action`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#14 |
| 目标路由 | `/dataquery/recemanagement/264` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 账单/房产/客户 | 文本 |
| `billingPeriod` | 账期 | 月份 |
| `status` | 账单状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billNo` | 账单号 | 文本 |
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `feeName` | 费用项目 | 文本 |
| `amount` | 应收金额 | 金额 |
| `outstandingAmount` | 未收金额 | 金额 |
| `status` | 状态 | 状态 |
| `version` | 版本 | 数值 |

### 验收记录

- 业务假设：账单修改走调账/作废，不直接覆盖金额。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 15. 日结明细表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/daily-settlement-details` |
| 波次 / 领域 | A / `reports` |
| 页面实现 | `report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；导出 `finance:export`；打印 `finance:print` |
| 主要操作 | `query`、`close-day`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#15 |
| 目标路由 | `/dataquery/dailyStatement/400` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `settlementDate` | 日结日期 | 日期 |
| `cashierId` | 收银员 | 下拉选择 |
| `paymentChannel` | 支付渠道 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `settlementNo` | 日结单号 | 文本 |
| `cashierName` | 收银员 | 文本 |
| `paymentChannel` | 支付渠道 | 状态 |
| `transactionCount` | 交易笔数 | 数值 |
| `collectedAmount` | 实收金额 | 金额 |
| `refundedAmount` | 退款金额 | 金额 |
| `netAmount` | 净额 | 金额 |

### 验收记录

- 业务假设：日结口径在 G5 依据渠道和冲正时点确认。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 16. 调账记录

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/adjustments` |
| 波次 / 领域 | A / `finance` |
| 页面实现 | `ledger-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；写操作 `finance:adjust`；导出 `finance:export` |
| 主要操作 | `create`、`approve`、`reject`、`view-ledger`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#16 |
| 目标路由 | `/dataquery/tiaozhangjilu/661` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 调账日期 | 日期范围 |
| `adjustmentType` | 调账类型 | 下拉选择 |
| `status` | 审批状态 | 下拉选择 |
| `keyword` | 账单/客户 | 文本 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `adjustmentNo` | 调账单号 | 文本 |
| `billNo` | 账单号 | 文本 |
| `adjustmentType` | 类型 | 状态 |
| `beforeAmount` | 调整前 | 金额 |
| `changeAmount` | 调整额 | 金额 |
| `afterAmount` | 调整后 | 金额 |
| `status` | 状态 | 状态 |
| `createdAt` | 创建时间 | 日期时间 |

### 验收记录

- 业务假设：调账采用不可变分录并记录审批链。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 17. 数据迁移

| 项目 | 定义 |
|---|---|
| 路由 | `/system/migrations` |
| 波次 / 领域 | A / `system` |
| 页面实现 | `migration-center` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `migration:read`；写操作 `migration:write`；导入 `migration:import`；导出 `migration:export` |
| 主要操作 | `download-template`、`upload`、`validate`、`execute`、`reconcile`、`rollback`、`export-errors` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#17 |
| 目标路由 | `/dataTransfer/dataTransfer/263` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `batchNo` | 批次号 | 文本 |
| `dataType` | 数据类型 | 下拉选择 |
| `status` | 批次状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `batchNo` | 批次号 | 文本 |
| `dataType` | 数据类型 | 状态 |
| `sourceFile` | 源文件 | 文本 |
| `totalCount` | 总数 | 数值 |
| `successCount` | 成功 | 数值 |
| `errorCount` | 失败 | 数值 |
| `status` | 状态 | 状态 |
| `createdAt` | 创建时间 | 日期时间 |

### 验收记录

- 业务假设：G4 已落地五层迁移、审批、重放、对账与逆序回滚；首批受控资源为项目、楼栋、房屋、客户和客户资产关系。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 18. 企业管理

| 项目 | 定义 |
|---|---|
| 路由 | `/enterprise/enterprises` |
| 波次 / 领域 | B / `iam` |
| 页面实现 | `catalog` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `iam:read`；写操作 `iam:write`；导出 `iam:export` |
| 主要操作 | `create`、`edit`、`disable`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#18 |
| 目标路由 | `/company/company/103` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 企业名称/编码 | 文本 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 企业编码 | 文本 |
| `name` | 企业名称 | 文本 |
| `organizationCount` | 组织数 | 数值 |
| `projectCount` | 项目数 | 数值 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：停用前校验在用组织、人员和项目。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 19. 岗位管理

| 项目 | 定义 |
|---|---|
| 路由 | `/enterprise/positions` |
| 波次 / 领域 | B / `iam` |
| 页面实现 | `catalog` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `iam:read`；写操作 `iam:write`；导出 `iam:export` |
| 主要操作 | `create`、`edit`、`disable`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#19 |
| 目标路由 | `/system/post/210` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 岗位名称/编码 | 文本 |
| `organizationId` | 所属组织 | 树选择 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 岗位编码 | 文本 |
| `name` | 岗位名称 | 文本 |
| `organizationName` | 所属组织 | 文本 |
| `employeeCount` | 人员数 | 数值 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：岗位隶属组织，人员可有主岗位和兼岗。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 20. 网格管理

| 项目 | 定义 |
|---|---|
| 路由 | `/archives/grids` |
| 波次 / 领域 | B / `archives` |
| 页面实现 | `tree-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `property:read`；写操作 `property:write`；导入 `property:import`；导出 `property:export` |
| 主要操作 | `create`、`edit`、`assign-assets`、`assign-manager`、`import`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#20 |
| 目标路由 | `/grid/grid/546` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `communityId` | 小区 | 下拉选择 |
| `keyword` | 网格名称/编码 | 文本 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 网格编码 | 文本 |
| `name` | 网格名称 | 文本 |
| `parentName` | 上级网格 | 文本 |
| `buildingCount` | 楼栋数 | 数值 |
| `managerName` | 网格员 | 文本 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：网格可分层并绑定楼栋/房产。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 21. 车位信息

| 项目 | 定义 |
|---|---|
| 路由 | `/archives/parking-spaces` |
| 波次 / 领域 | B / `archives` |
| 页面实现 | `asset-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `property:read`；写操作 `property:write`；导入 `property:import`；导出 `property:export` |
| 主要操作 | `create`、`edit`、`bind-customer`、`bind-vehicle`、`import`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#21 |
| 目标路由 | `/park/parkspace/134` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 车位编号/客户/车牌 | 文本 |
| `parkingArea` | 停车区域 | 下拉选择 |
| `useStatus` | 使用状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `code` | 车位编号 | 文本 |
| `parkingArea` | 停车区域 | 文本 |
| `spaceType` | 车位类型 | 状态 |
| `customerName` | 关联客户 | 脱敏文本 |
| `vehiclePlateMasked` | 车牌号 | 脱敏文本 |
| `useStatus` | 使用状态 | 状态 |

### 验收记录

- 业务假设：车位按统一资产建模，车辆关系有生效期。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 22. 仪表管理

| 项目 | 定义 |
|---|---|
| 路由 | `/archives/meters` |
| 波次 / 领域 | B / `metering` |
| 页面实现 | `meter-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `meter:read`；写操作 `meter:write`；导入 `meter:import`；导出 `meter:export` |
| 主要操作 | `create`、`edit`、`bind-asset`、`replace`、`import`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#22 |
| 目标路由 | `/meter/meter/128` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 表号/房产 | 文本 |
| `meterType` | 仪表类型 | 下拉选择 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `meterNo` | 仪表编号 | 文本 |
| `meterType` | 仪表类型 | 状态 |
| `assetName` | 关联资产 | 文本 |
| `multiplier` | 倍率 | 数值 |
| `lastReading` | 末次读数 | 数值 |
| `lastReadAt` | 末次抄表时间 | 日期时间 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：换表通过专用流程保留读数连续性。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 23. 票据管理

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/instruments` |
| 波次 / 领域 | B / `finance` |
| 页面实现 | `instrument-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；写操作 `finance:instrument-write`；导入 `finance:import`；导出 `finance:export` |
| 主要操作 | `register`、`issue`、`return`、`void`、`import`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#23 |
| 目标路由 | `/infee/bill/151` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `instrumentType` | 票据类型 | 下拉选择 |
| `numberRange` | 号码段 | 文本 |
| `status` | 状态 | 下拉选择 |
| `holderId` | 领用人 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `instrumentType` | 票据类型 | 状态 |
| `startNo` | 起始号码 | 文本 |
| `endNo` | 结束号码 | 文本 |
| `holderName` | 领用人 | 文本 |
| `usedCount` | 已用数 | 数值 |
| `remainingCount` | 剩余数 | 数值 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：纸质票据与电子票据使用同一库存状态机。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 24. 抄表

| 项目 | 定义 |
|---|---|
| 路由 | `/metering/readings` |
| 波次 / 领域 | B / `metering` |
| 页面实现 | `meter-reading-workbench` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `meter:read`；写操作 `meter:write`；导入 `meter:import`；导出 `meter:export` |
| 主要操作 | `create-batch`、`enter-reading`、`import`、`validate`、`approve`、`generate-charge`、`export-errors` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#24 |
| 目标路由 | `/infee/meterread/156` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `batchNo` | 批次号 | 文本 |
| `readingPeriod` | 抄表周期 | 月份 |
| `meterType` | 仪表类型 | 下拉选择 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `meterNo` | 仪表编号 | 文本 |
| `assetName` | 房产 | 文本 |
| `previousReading` | 上次读数 | 数值 |
| `currentReading` | 本次读数 | 数值 |
| `usageAmount` | 用量 | 数值 |
| `readAt` | 抄表时间 | 日期时间 |
| `validationStatus` | 校验状态 | 状态 |

### 验收记录

- 业务假设：异常读数需复核后才允许审核。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 25. 折扣管理

| 项目 | 定义 |
|---|---|
| 路由 | `/fees/discounts` |
| 波次 / 领域 | B / `fees` |
| 页面实现 | `discount-workspace` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `fee:read`；写操作 `fee:discount-write`；导出 `fee:export` |
| 主要操作 | `create`、`approve`、`reject`、`terminate`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#25 |
| 目标路由 | `/infee/discount/193` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 客户/房产/折扣单 | 文本 |
| `discountType` | 折扣类型 | 下拉选择 |
| `status` | 审批状态 | 下拉选择 |
| `dateRange` | 生效日期 | 日期范围 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `discountNo` | 折扣单号 | 文本 |
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `discountType` | 折扣类型 | 状态 |
| `discountValue` | 折扣值 | 数值 |
| `effectiveFrom` | 生效日期 | 日期 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：折扣规则版本化且不可追溯覆盖。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 26. 批量冲预收款

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/prepayment-batch-offsets` |
| 波次 / 领域 | B / `finance` |
| 页面实现 | `batch-offset-workflow` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；写操作 `finance:offset`；导入 `finance:import`；导出 `finance:export` |
| 主要操作 | `preview`、`select`、`execute`、`view-task`、`export-errors` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#26 |
| 目标路由 | `/infee/plcysk/446` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billingPeriod` | 账期 | 月份 |
| `communityId` | 小区 | 下拉选择 |
| `feeDefinitionId` | 费用项目 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `customerName` | 客户 | 脱敏文本 |
| `assetName` | 房产 | 文本 |
| `prepaymentBalance` | 预收余额 | 金额 |
| `outstandingAmount` | 欠费金额 | 金额 |
| `offsetAmount` | 拟冲金额 | 金额 |
| `validationStatus` | 校验结果 | 状态 |

### 验收记录

- 业务假设：批量抵扣逐笔入账，允许部分失败并可重试。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 27. 交易汇总

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/transaction-summary` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；导出 `finance:export`；打印 `finance:print` |
| 主要操作 | `query`、`switch-dimension`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#27 |
| 目标路由 | `/dataquery/summary/268` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 交易日期 | 日期范围 |
| `paymentChannel` | 支付渠道 | 下拉选择 |
| `cashierId` | 收银员 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `groupLabel` | 汇总维度 | 文本 |
| `transactionCount` | 交易笔数 | 数值 |
| `receivableAmount` | 应收金额 | 金额 |
| `collectedAmount` | 实收金额 | 金额 |
| `refundAmount` | 退款金额 | 金额 |
| `netAmount` | 净额 | 金额 |

### 验收记录

- 业务假设：交易日期按支付成功时间归属。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 28. 交易明细

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/transaction-details` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；导出 `finance:export`；打印 `finance:print` |
| 主要操作 | `query`、`view`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#28 |
| 目标路由 | `/dataquery/jiaoyimingxi/290` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 交易日期 | 日期范围 |
| `transactionNo` | 交易号 | 文本 |
| `paymentChannel` | 支付渠道 | 下拉选择 |
| `status` | 交易状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `transactionNo` | 交易号 | 文本 |
| `paidAt` | 交易时间 | 日期时间 |
| `customerName` | 客户 | 脱敏文本 |
| `assetName` | 房产 | 文本 |
| `paymentChannel` | 支付渠道 | 状态 |
| `amount` | 交易金额 | 金额 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：退款和冲正通过原交易号关联。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 29. 交易记录

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/payments` |
| 波次 / 领域 | B / `finance` |
| 页面实现 | `payment-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；写操作 `finance:reverse`；导出 `finance:export`；打印 `finance:print` |
| 主要操作 | `view`、`reverse`、`reprint-receipt`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#29 |
| 目标路由 | `/dataquery/transactiondetail/228` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 交易日期 | 日期范围 |
| `keyword` | 交易号/客户/房产 | 文本 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `transactionNo` | 交易号 | 文本 |
| `paymentOrderNo` | 支付订单号 | 文本 |
| `paidAt` | 交易时间 | 日期时间 |
| `payerName` | 付款人 | 脱敏文本 |
| `amount` | 金额 | 金额 |
| `channel` | 渠道 | 状态 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：已日结交易冲正产生反向分录。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 30. 收缴率报表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/collection-rate` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `metric-report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`drill-down`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#30 |
| 目标路由 | `/dataquery/shoujiaolv/254` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billingPeriodRange` | 账期范围 | 月份范围 |
| `organizationScope` | 组织范围 | 树选择 |
| `feeDefinitionId` | 费用项目 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `scopeName` | 统计对象 | 文本 |
| `receivableAmount` | 应收金额 | 金额 |
| `collectedAmount` | 已收金额 | 金额 |
| `outstandingAmount` | 欠费金额 | 金额 |
| `collectionRate` | 收缴率 | 百分比 |

### 验收记录

- 业务假设：收缴率分子分母口径在 G7 固化。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 31. 清欠率

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/arrears-clearance-rate` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `metric-report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`drill-down`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#31 |
| 目标路由 | `/dataquery/qingqianlv/270` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 清欠日期 | 日期范围 |
| `arrearsPeriodRange` | 欠费账期 | 月份范围 |
| `organizationScope` | 组织范围 | 树选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `scopeName` | 统计对象 | 文本 |
| `openingArrears` | 期初欠费 | 金额 |
| `clearedAmount` | 已清欠 | 金额 |
| `closingArrears` | 期末欠费 | 金额 |
| `clearanceRate` | 清欠率 | 百分比 |

### 验收记录

- 业务假设：清欠按历史欠费在统计期内的回收计算。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 32. 收清欠汇总表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/collection-clearance-summary` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `metric-report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`drill-down`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#32 |
| 目标路由 | `/dataquery/acquittanceCollect/278` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 统计日期 | 日期范围 |
| `organizationScope` | 组织范围 | 树选择 |
| `feeDefinitionId` | 费用项目 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `scopeName` | 统计对象 | 文本 |
| `currentReceivable` | 本期应收 | 金额 |
| `currentCollected` | 本期实收 | 金额 |
| `historicalArrears` | 历史欠费 | 金额 |
| `arrearsCleared` | 已清历史欠费 | 金额 |
| `collectionRate` | 收缴率 | 百分比 |
| `clearanceRate` | 清欠率 | 百分比 |

### 验收记录

- 业务假设：收缴与清欠两套分母独立展示。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 33. 收费明细报表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/charge-details` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`view-transaction`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#33 |
| 目标路由 | `/dataquery/infeedetail/229` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 收费日期 | 日期范围 |
| `communityId` | 小区 | 下拉选择 |
| `feeDefinitionId` | 费用项目 | 下拉选择 |
| `paymentChannel` | 支付渠道 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `paidAt` | 收费时间 | 日期时间 |
| `receiptNo` | 收据号 | 文本 |
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `feeName` | 费用项目 | 文本 |
| `billingPeriod` | 账期 | 月份 |
| `amount` | 实收金额 | 金额 |
| `cashierName` | 收银员 | 文本 |

### 验收记录

- 业务假设：按交易分摊明细展示费用项目。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 34. 预收款报表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/prepayments` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `ledger-report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`view-ledger`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#34 |
| 目标路由 | `/dataquery/yushoukuanbaobiao/318` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 发生日期 | 日期范围 |
| `keyword` | 客户/房产 | 文本 |
| `entryType` | 分录类型 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `occurredAt` | 发生时间 | 日期时间 |
| `customerName` | 客户 | 脱敏文本 |
| `assetName` | 房产 | 文本 |
| `entryType` | 分录类型 | 状态 |
| `creditAmount` | 增加 | 金额 |
| `debitAmount` | 减少 | 金额 |
| `balance` | 余额 | 金额 |
| `referenceNo` | 关联单号 | 文本 |

### 验收记录

- 业务假设：余额由不可变预收分录汇总。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 35. 费用情况表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/fee-status` |
| 波次 / 领域 | B / `reports` |
| 页面实现 | `report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`drill-down`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#35 |
| 目标路由 | `/dataquery/feiyongqingkuang/317` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billingPeriodRange` | 账期范围 | 月份范围 |
| `communityId` | 小区 | 下拉选择 |
| `feeDefinitionId` | 费用项目 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `feeName` | 费用项目 | 文本 |
| `receivableCount` | 应收户数 | 数值 |
| `receivableAmount` | 应收金额 | 金额 |
| `collectedCount` | 已收户数 | 数值 |
| `collectedAmount` | 已收金额 | 金额 |
| `outstandingAmount` | 未收金额 | 金额 |

### 验收记录

- 业务假设：户数按房产-费用项目去重。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 36. 押金记录表

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/deposits` |
| 波次 / 领域 | B / `finance` |
| 页面实现 | `ledger-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；写操作 `finance:deposit-write`；导出 `finance:export`；打印 `finance:print` |
| 主要操作 | `collect`、`refund`、`void`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#36 |
| 目标路由 | `/dataquery/depositRecord/401` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 发生日期 | 日期范围 |
| `keyword` | 押金单/客户/房产 | 文本 |
| `status` | 押金状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `depositNo` | 押金单号 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `assetName` | 房产 | 文本 |
| `depositType` | 押金类型 | 状态 |
| `receivedAmount` | 收取金额 | 金额 |
| `refundedAmount` | 退还金额 | 金额 |
| `balance` | 余额 | 金额 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：退款不得超过可退余额，必须关联原押金。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 37. 数据字典

| 项目 | 定义 |
|---|---|
| 路由 | `/system/dictionaries` |
| 波次 / 领域 | B / `system` |
| 页面实现 | `catalog` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `dictionary:read`；写操作 `dictionary:write`；导入 `dictionary:import`；导出 `dictionary:export` |
| 主要操作 | `create`、`edit`、`reorder`、`disable`、`import`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#37 |
| 目标路由 | `/common/dict/78` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dictionaryType` | 字典类型 | 下拉选择 |
| `keyword` | 代码/名称 | 文本 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dictionaryType` | 字典类型 | 文本 |
| `code` | 字典代码 | 文本 |
| `displayName` | 显示名称 | 文本 |
| `sortOrder` | 排序 | 数值 |
| `enabled` | 启用状态 | 状态 |
| `updatedAt` | 更新时间 | 日期时间 |

### 验收记录

- 业务假设：系统保留项不可删除，只可停用。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 38. 看板配置

| 项目 | 定义 |
|---|---|
| 路由 | `/dashboard/configuration` |
| 波次 / 领域 | C / `dashboard` |
| 页面实现 | `dashboard-designer` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `dashboard:read`；写操作 `dashboard:configure` |
| 主要操作 | `add-widget`、`reorder`、`configure`、`preview`、`publish` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#38 |
| 目标路由 | `/kanban/options/488` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `roleId` | 适用角色 | 下拉选择 |
| `projectId` | 适用项目 | 项目选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `widgetCode` | 组件编码 | 文本 |
| `widgetName` | 组件名称 | 文本 |
| `position` | 位置 | 文本 |
| `visible` | 是否显示 | 状态 |
| `refreshInterval` | 刷新间隔 | 数值 |

### 验收记录

- 业务假设：配置按角色和项目覆盖，保留系统默认。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 39. 第三方参数设置

| 项目 | 定义 |
|---|---|
| 路由 | `/system/third-party-settings` |
| 波次 / 领域 | C / `system` |
| 页面实现 | `integration-settings` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `integration:read`；写操作 `integration:write` |
| 主要操作 | `configure`、`test-connection`、`enable`、`disable`、`view-audit` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#39 |
| 目标路由 | `/infee/feeThirdpart/198` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `providerType` | 通道类型 | 下拉选择 |
| `status` | 连接状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `providerType` | 通道类型 | 状态 |
| `providerName` | 通道名称 | 文本 |
| `mode` | 运行模式 | 状态 |
| `endpointMasked` | 服务地址 | 脱敏文本 |
| `credentialStatus` | 凭据状态 | 状态 |
| `lastCheckedAt` | 末次检测 | 日期时间 |

### 验收记录

- 业务假设：密钥只写不回显，默认模拟模式。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 40. 换发票

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/invoice-replacements` |
| 波次 / 领域 | C / `finance` |
| 页面实现 | `invoice-workflow` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `invoice:read`；写操作 `invoice:write`；打印 `invoice:print` |
| 主要操作 | `verify-original`、`void-original`、`issue-replacement`、`print`、`view-audit` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#40 |
| 目标路由 | `/infee/invoice/365` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `keyword` | 原发票号/交易号 | 文本 |
| `dateRange` | 开票日期 | 日期范围 |
| `status` | 状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `originalInvoiceNo` | 原发票号 | 文本 |
| `replacementInvoiceNo` | 新发票号 | 文本 |
| `transactionNo` | 交易号 | 文本 |
| `buyerName` | 购方名称 | 脱敏文本 |
| `amount` | 开票金额 | 金额 |
| `status` | 状态 | 状态 |
| `replacedAt` | 换票时间 | 日期时间 |

### 验收记录

- 业务假设：无税控授权时使用模拟发票适配器并显式标识。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 41. 批量打印收据

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/receipt-batch-print` |
| 波次 / 领域 | C / `finance` |
| 页面实现 | `print-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `finance:read`；打印 `finance:print` |
| 主要操作 | `select`、`preview`、`batch-print`、`reprint`、`view-task` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#41 |
| 目标路由 | `/dataquery/transactionBatch/512` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 收费日期 | 日期范围 |
| `receiptStatus` | 收据状态 | 下拉选择 |
| `cashierId` | 收银员 | 下拉选择 |
| `keyword` | 收据号/房产 | 文本 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `selected` | 选择 | 多选 |
| `receiptNo` | 收据号 | 文本 |
| `paidAt` | 收费时间 | 日期时间 |
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `amount` | 金额 | 金额 |
| `printCount` | 打印次数 | 数值 |

### 验收记录

- 业务假设：批量打印走异步任务并记录模板版本。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 42. 账单通知

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/bill-notifications` |
| 波次 / 领域 | C / `finance` |
| 页面实现 | `notification-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `notification:read`；写操作 `notification:send`；导入 `notification:import`；导出 `notification:export` |
| 主要操作 | `preview`、`select`、`send`、`retry`、`view-task`、`export-errors` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#42 |
| 目标路由 | `/dataquery/billnotice/267` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `billingPeriod` | 账期 | 月份 |
| `channel` | 通知渠道 | 下拉选择 |
| `deliveryStatus` | 发送状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `customerName` | 客户 | 脱敏文本 |
| `assetName` | 房产 | 文本 |
| `billingPeriod` | 账期 | 月份 |
| `billAmount` | 账单金额 | 金额 |
| `channel` | 通知渠道 | 状态 |
| `deliveryStatus` | 发送状态 | 状态 |
| `sentAt` | 发送时间 | 日期时间 |

### 验收记录

- 业务假设：无短信/微信授权时使用模拟发送器。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 43. 综合查询表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/comprehensive-query` |
| 波次 / 领域 | C / `reports` |
| 页面实现 | `query-builder` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`save-filter`、`configure-columns`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#43 |
| 目标路由 | `/dataquery/multimeter/295` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `subjectType` | 查询主题 | 下拉选择 |
| `dateRange` | 业务日期 | 日期范围 |
| `organizationScope` | 组织范围 | 树选择 |
| `keyword` | 客户/房产/单号 | 文本 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `businessType` | 业务类型 | 状态 |
| `businessNo` | 业务单号 | 文本 |
| `occurredAt` | 发生时间 | 日期时间 |
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `amount` | 金额 | 金额 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：只开放预定义主题，不允许任意 SQL。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 44. 折扣明细表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/discount-details` |
| 波次 / 领域 | C / `reports` |
| 页面实现 | `report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `report:read`；导出 `report:export`；打印 `report:print` |
| 主要操作 | `query`、`view-discount`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#44 |
| 目标路由 | `/dataquery/discountDetail/273` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 折扣日期 | 日期范围 |
| `discountType` | 折扣类型 | 下拉选择 |
| `keyword` | 客户/房产 | 文本 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `discountNo` | 折扣单号 | 文本 |
| `assetName` | 房产 | 文本 |
| `customerName` | 客户 | 脱敏文本 |
| `feeName` | 费用项目 | 文本 |
| `originalAmount` | 原金额 | 金额 |
| `discountAmount` | 折扣金额 | 金额 |
| `finalAmount` | 折后金额 | 金额 |
| `approvedAt` | 审批时间 | 日期时间 |

### 验收记录

- 业务假设：折扣金额与账单优惠分录可对账。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 45. 过户查询

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/ownership-transfers` |
| 波次 / 领域 | C / `reports` |
| 页面实现 | `relationship-report` |
| 目标等级 | L1 / L2 / L3 |
| 权限 | 页面读取 `property:read`；导出 `property:export-sensitive`；打印 `property:print` |
| 主要操作 | `query`、`view-timeline`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#45 |
| 目标路由 | `/dataquery/transfer/294` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 过户日期 | 日期范围 |
| `communityId` | 小区 | 下拉选择 |
| `keyword` | 房产/原客户/新客户 | 文本 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `transferNo` | 过户单号 | 文本 |
| `assetName` | 房产 | 文本 |
| `previousCustomerName` | 原客户 | 脱敏文本 |
| `newCustomerName` | 新客户 | 脱敏文本 |
| `effectiveDate` | 生效日期 | 日期 |
| `operatorName` | 操作人 | 文本 |
| `status` | 状态 | 状态 |

### 验收记录

- 业务假设：导出个人信息需要额外权限和审计。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 46. 提醒明细

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/reminders` |
| 波次 / 领域 | C / `reports` |
| 页面实现 | `notification-report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `notification:read`；导出 `notification:export`；打印 `notification:print` |
| 主要操作 | `query`、`view-content`、`retry`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#46 |
| 目标路由 | `/dataquery/remind/296` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 提醒日期 | 日期范围 |
| `reminderType` | 提醒类型 | 下拉选择 |
| `deliveryStatus` | 发送状态 | 下拉选择 |
| `keyword` | 客户/房产 | 文本 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `reminderNo` | 提醒编号 | 文本 |
| `reminderType` | 提醒类型 | 状态 |
| `customerName` | 客户 | 脱敏文本 |
| `assetName` | 房产 | 文本 |
| `channel` | 渠道 | 状态 |
| `deliveryStatus` | 发送状态 | 状态 |
| `sentAt` | 发送时间 | 日期时间 |

### 验收记录

- 业务假设：通知正文按最小必要原则脱敏留档。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 47. 发票统计表

| 项目 | 定义 |
|---|---|
| 路由 | `/reports/invoice-statistics` |
| 波次 / 领域 | C / `reports` |
| 页面实现 | `metric-report` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `invoice:read`；导出 `invoice:export`；打印 `invoice:print` |
| 主要操作 | `query`、`drill-down`、`export`、`print` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#47 |
| 目标路由 | `/dataquery/fapiaotongji/389` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 开票日期 | 日期范围 |
| `invoiceType` | 发票类型 | 下拉选择 |
| `invoiceStatus` | 发票状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `groupLabel` | 统计对象 | 文本 |
| `issuedCount` | 开票份数 | 数值 |
| `issuedAmount` | 开票金额 | 金额 |
| `voidedCount` | 作废份数 | 数值 |
| `voidedAmount` | 作废金额 | 金额 |
| `redCount` | 红冲份数 | 数值 |
| `redAmount` | 红冲金额 | 金额 |

### 验收记录

- 业务假设：生产税务口径依赖税控授权，内部以模拟契约验证。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 48. 银行信托

| 项目 | 定义 |
|---|---|
| 路由 | `/finance/bank-trust` |
| 波次 / 领域 | D / `finance` |
| 页面实现 | `external-adapter-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `bank:read`；写操作 `bank:write`；导入 `bank:import`；导出 `bank:export` |
| 主要操作 | `create-batch`、`submit-simulation`、`import-result`、`reconcile`、`view-task`、`export-errors` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#48 |
| 目标路由 | `/dataquery/banktrust/308` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 对账日期 | 日期范围 |
| `bankChannel` | 银行通道 | 下拉选择 |
| `reconcileStatus` | 对账状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `trustNo` | 托收批次号 | 文本 |
| `bankChannel` | 银行通道 | 状态 |
| `submittedCount` | 提交笔数 | 数值 |
| `submittedAmount` | 提交金额 | 金额 |
| `successCount` | 成功笔数 | 数值 |
| `successAmount` | 成功金额 | 金额 |
| `reconcileStatus` | 对账状态 | 状态 |

### 验收记录

- 业务假设：缺少银行协议时只运行模拟适配器，并标识未接生产通道。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

## 49. 访客记录

| 项目 | 定义 |
|---|---|
| 路由 | `/security/visitor-records` |
| 波次 / 领域 | D / `security` |
| 页面实现 | `external-adapter-workspace` |
| 目标等级 | L1 / L2 |
| 权限 | 页面读取 `visitor:read`；写操作 `visitor:write`；导入 `visitor:import`；导出 `visitor:export-sensitive` |
| 主要操作 | `register`、`check-in`、`check-out`、`import-simulation`、`export` |
| 必备状态 | 正常、加载中、空数据、无权限、校验失败、请求失败、并发/业务冲突、批量部分失败 |
| 来源索引 | PMS3页面与接口证据附录#49 |
| 目标路由 | `/visitorChargeOff/visitorLog/668` |
| 当前目录状态 | G2 路由、查询模型、列模型、权限码与状态骨架已登记；业务闭环、数据对账和最终视觉证据按对应 Goal 阶段验收 |

### 查询模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `dateRange` | 来访日期 | 日期范围 |
| `keyword` | 访客/受访人/房产 | 文本 |
| `visitStatus` | 来访状态 | 下拉选择 |

### 列模型

| 字段键 | 显示名称 | 类型 |
|---|---|---|
| `visitNo` | 访客单号 | 文本 |
| `visitorNameMasked` | 访客姓名 | 脱敏文本 |
| `visitorMobileMasked` | 联系方式 | 脱敏文本 |
| `hostName` | 受访人 | 脱敏文本 |
| `assetName` | 访问房产 | 文本 |
| `checkInAt` | 进入时间 | 日期时间 |
| `checkOutAt` | 离开时间 | 日期时间 |
| `visitStatus` | 状态 | 状态 |

### 验收记录

- 业务假设：无门禁授权时使用模拟设备契约，访客个人信息默认脱敏。
- 结构证据：待填写页面截图、目标扫描索引与三视口差异结果。
- 功能证据：待填写 API、组件、E2E、权限与失败路径测试。
- 数据证据：待填写数量/金额/关系对账；不适用时说明原因。
- 外部边界：涉及真实支付、发票、银行、通知、IoT 或生产数据时，必须标明真实、模拟或待授权。

