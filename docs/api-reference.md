# API 参考

## 通用约定

- 基础路径：`/api/v1`；OpenAPI：`/v3/api-docs`；Swagger UI：`/docs`。
- 除登录、健康检查和 OpenAPI 外，所有请求使用 `Authorization: Bearer <JWT>`。
- 前端为每个请求生成 `X-Request-Id`；后端会校验并回写该响应头，缺失或不安全时生成 UUID。
- 写接口先校验 RBAC 权限，再校验 `communityId` 是否在登录人的项目范围内。
- 金额和用量在 JSON 中以字符串输出，后端使用 `BigDecimal`，数据库使用 `DECIMAL`。
- 幂等写操作使用 `Idempotency-Key`。相同业务和相同键返回已存在结果，不重复落账。
- 错误结构包含 `code`、`message`、`requestId`、`timestamp` 和可选 `violations`。

## 契约生成与漂移门禁

- 后端提交版契约：`apps/pms-api/src/test/resources/openapi-contract.json`；
- 前端生成类型：`apps/admin-web/src/api/generated.ts`；
- `npm run api:check` 比较提交版契约与生成类型，已包含在 `npm test`；
- `npm run api:check:live` 额外比较运行中 `/v3/api-docs`，环境相关的 `servers` 字段会先规范化；
- Spring 集成测试 `openApiContractMatchesCommittedSnapshot` 从后端侧阻止未同步的契约变更；
- 只有确认 API 变更符合验收卡后才运行 `npm run api:generate`，并同时提交 JSON 快照、TypeScript 类型和相关测试。

## 认证和平台

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | 本地账号登录，返回 JWT、角色、权限、项目范围和首次改密标识；失败达到阈值返回 429 |
| GET | `/auth/me` | 当前用户与权限快照 |
| PUT | `/auth/change-password` | 校验当前密码和强密码策略，修改密码并签发新令牌；旧令牌立即失效 |
| POST | `/auth/sessions:revoke` | 递增服务端会话版本，撤销当前账号的全部既有令牌 |
| GET | `/runtime` | 运行模式和适配器状态，不返回秘密 |
| GET | `/dashboard` | 项目统计、收费概览和数据质量 |

## 通用数据接口

`GET /data/{resource}` 支持 `communityId`、`keyword`、`status`、`category`、`page`、`size` 和白名单 `sort`。返回 `items`、`page`、`size`、`total`、`totalPages`。

高置信档案资源支持：

- `POST /data/{resource}`；
- `PUT /data/{resource}/{id}?version=n`（乐观锁）；
- `DELETE /data/{resource}/{id}?version=n`（软停用，不物理删除）。

资源包括小区、网格、楼栋、单元、资产、客户、客户资产关系、车辆、仪表、费用定义/标准/版本/分配、账单、支付订单/流水、预收、押金、收据、抄表批次/读数、公摊、换表、字典、用户和审计等。

G3 已将 `grids`、`buildings`、`units`、`assets` 和 `customers` 接到真实可写资源。档案写入会校验同项目引用、层级关系、面积/日期/状态；创建房屋或车位会原子补齐对应类型明细。停用资产会同时设置 `enabled=false` 与 `operation_status=INACTIVE`，存在下级或有效业务引用时返回 `409 RESOURCE_IN_USE`。

## 企业、组织与权限管理

以下接口要求 `iam:read`；写接口同时要求 `iam:write`。账号读取和业务数据仍受服务端项目范围约束，菜单隐藏不构成授权。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/iam/enterprises` | 查询或创建企业 |
| PUT | `/iam/enterprises/{id}` | 按版本更新企业状态和名称 |
| GET/POST | `/iam/organizations` | 查询或创建组织树节点 |
| PUT | `/iam/organizations/{id}` | 更新组织归属、顺序和状态，拒绝循环父子关系 |
| GET/POST | `/iam/positions` | 查询或创建岗位 |
| PUT | `/iam/positions/{id}` | 更新岗位及组织归属 |
| GET/POST | `/iam/employees` | 查询或创建人员 |
| PUT | `/iam/employees/{id}` | 更新人员、岗位和在离职状态 |
| GET | `/iam/permissions` | 查询权限字典 |
| GET/POST | `/iam/roles` | 查询或创建角色及权限集合 |
| PUT | `/iam/roles/{id}` | 按版本更新角色、启停和权限集合 |
| GET/POST | `/iam/users` | 查询或创建账号及角色/项目范围 |
| PUT | `/iam/users/{id}` | 按版本更新账号、角色和项目范围 |
| PUT | `/iam/users/{id}/password` | 重置密码并设置首次改密标识 |
| GET | `/iam/projects` | 仅返回当前操作者可管理的有效项目 |

IAM 写接口统一使用版本号防止静默覆盖。启用账号只能关联在职人员、启用角色和启用项目；人员、企业角色和项目范围必须保持企业一致。仍被启用下级资源或账号引用的企业、组织、岗位、人员和角色不能直接停用，返回 `409 IAM_RESOURCE_IN_USE`，操作者必须先按账号→人员→岗位/组织/角色的依赖顺序处理。

## 基础档案与客户关系

以下接口要求 `property:read`；关系、产权和档案写操作同时要求 `property:write`。所有接口都在服务端校验 `communityId`，不能依赖前端项目选择器形成隔离。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/property/tree` | 按项目和可选资产类型返回网格—楼栋—单元—资产树与稳定计数 |
| GET | `/property/assets` | 分页查询房屋/车位，返回层级、有效客户数和版本 |
| GET | `/property/assets/{id}` | 资产档案、类型明细、客户关系、车辆、仪表和关系事件时间线 |
| GET | `/property/customers` | 按关键字、客户类型和 ACTIVE/INACTIVE 状态分页查询 |
| GET | `/property/customers/{id}` | 客户档案、资产关系、车辆和关系事件时间线 |
| POST | `/property/relations` | 幂等创建 OWNER/CO_OWNER/TENANT/OCCUPANT 关系 |
| POST | `/property/relations/{id}:end` | 按版本和生效日幂等结束关系，历史行保留 |
| POST | `/property/assets/{id}:transfer` | 幂等执行产权变更，结束原 OWNER/CO_OWNER 并创建新 OWNER |
| GET | `/property/imports/template` | 下载 GRID/BUILDING/UNIT/ASSET/CUSTOMER/RELATION 的 UTF-8 CSV 模板 |
| POST | `/property/imports:validate` | 最多 500 行的无写入预校验，逐行返回错误、警告和汇总 |

关系写入需要 `Idempotency-Key`；同一项目和请求键重放返回原结果。数据库生成列禁止重复有效关系，乐观锁防止静默覆盖；类型化响应不会返回手机、证件原文。档案导入预校验只检查模板、必填项、项目引用、重复编码、面积、日期和关系重叠，不会实际写库；需批量写入时使用下述 G4 迁移中心。

## 数据迁移中心

读取要求 `migration:read`，创建和校验要求 `migration:import`，审批、执行、对账和回滚要求 `migration:write`；审批与回滚还要求 `PLATFORM_ADMIN`。所有批次均绑定 `communityId` 并在服务端重验项目范围。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/migrations/batches` | 按项目、状态和关键字分页查询批次 |
| GET | `/migrations/batches/{id}` | 查看五层计数、隔离错误、对象映射、对账和状态轨迹；写权限用户可见回滚凭证 |
| POST | `/migrations/batches` | 提交最多 500 条 JSON 来源行并写入不可变 Raw 层；同一 SHA-256 + 映射版本安全重放 |
| POST | `/migrations/batches/{id}:validate` | 重建 Quarantine、Canonical、Staging；错误行不写生产表 |
| POST | `/migrations/batches/{id}:approve` | 平台管理员审批；部分失败批次必须显式 `confirmPartial=true` |
| POST | `/migrations/batches/{id}:execute` | 按 PROJECT→BUILDING→ASSET→CUSTOMER→RELATION 依赖顺序原子写入并记录反向变更 |
| POST | `/migrations/batches/{id}:reconcile` | 对账项目/楼栋/房屋/客户/关系数量、建筑/可用面积、孤儿和重复有效关系 |
| POST | `/migrations/batches/{id}:rollback` | 校验回滚凭证后逆序删除本批新增生产对象，保留所有迁移和审计证据 |
| GET | `/migrations/template` | 下载首批字段 CSV 示例，不包含凭据或真实个人数据 |

首批白名单资源为 `PROJECT`、`BUILDING`、`ASSET`（仅 ROOM）、`CUSTOMER`、`RELATION`。来源 `sourceId` 只进入受控映射表，不作为公开 API 主键。批次命令使用 `expectedVersion` 防止并发覆盖；已完成执行和已回滚命令重复提交会返回 `replayed=true`。

## 费用

读取接口要求 `fee:read`，定义、标准、分配和生成任务写接口要求 `fee:write`；所有请求均由服务端重验 `communityId` 项目范围。金额使用 `BigDecimal`，版本/分配生效日为闭区间，重叠配置返回 `409`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/fees/definitions` | 查询或创建费用定义；包含会计科目、税务分类/税率、币种、精度、舍入和临时费用开关 |
| PUT | `/fees/definitions/{id}` | 按版本更新定义；历史账单快照不随之改变 |
| GET/POST | `/fees/standards` | 查询或创建标准及第一个不可变版本 |
| GET/POST | `/fees/standards/{id}/versions` | 查询版本或追加新生效版本；拒绝有效期重叠 |
| POST | `/fees/standards/{id}:disable` | 停用标准，不删除版本和历史引用 |
| GET | `/fees/allocations` | 按项目、标准、目标和生效日查询分配 |
| POST | `/fees/allocations:preview` | 只预览批量资产/仪表命中、冲突和可写数量 |
| POST | `/fees/allocations:assign` | 按请求键批量分配；同请求重放返回原结果 |
| POST | `/fees/allocations:cancel` | 按取消日期截断有效期并保留历史 |
| POST | `/receivables:preview` | 周期应收无写入试算，返回公式、定义、版本、分配、精度、舍入和整批校验值 |
| POST | `/receivable-jobs` | 使用 `Idempotency-Key` 创建周期异步任务；同键不同请求拒绝 |
| GET | `/receivable-jobs` | 按项目和 PERIODIC/TEMPORARY 类型查询任务 |
| GET | `/receivable-jobs/{id}` | 查看逐行结果、错误、账单引用和配置—应收—账单对账 |
| POST | `/temporary-receivables:preview` | 校验临时费用明细并生成金额快照 |
| POST | `/temporary-receivable-jobs` | 创建临时应收异步任务；仅接受允许临时使用的费用定义 |

兼容旧客户端的 `/fee-standards`、`/fee-allocations:batch-assign`、`/fee-allocations:batch-cancel` 和 `/receivable-jobs:preview` POST 别名暂时保留；新页面和生成契约使用上述 `/fees/*` 与 `/receivables:preview` 路径。

周期计算支持 `BUILDING_AREA`、`USABLE_AREA`、`FIXED` 和 `METER_USAGE`，再应用系数、最小/最大金额、定义级小数位和 `HALF_UP`/`HALF_EVEN`/`DOWN`/`UP`。创建任务后返回 QUEUED/RUNNING/COMPLETED/PARTIAL/FAILED 状态；业务失败保留错误明细和不一致对账，不静默覆盖既有账单。

## 收银和财务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/cashier/context` | 按客户、资产、账单号联合检索待收账单 |
| GET/POST | `/cashier/shifts` | 查询或幂等开启收银班次 |
| GET | `/cashier/shifts:current` | 当前登录人在项目内的开启班次和现金汇总 |
| POST | `/cashier/shifts/{id}:close` | 以乐观版本、实盘金额和差异证据交班 |
| POST | `/cashier/shifts/{id}:lock` | 锁定已关闭班次 |
| GET | `/finance/bills` | 按状态、关键词和截止日分页查询账单时间切片 |
| GET | `/finance/bills/{id}` | 账单、明细、调账和支付分摊证据 |
| GET | `/finance/arrears` | 截止日欠费明细、逾期天数和总额 |
| POST | `/payment-orders` | 以请求键和请求哈希幂等创建部分/合并支付意图 |
| POST | `/payment-orders/{id}:confirm-simulated` | 并发安全确认，生成唯一成功流水、分摊和号段收据 |
| POST | `/prepayment-accounts` | 获取或创建客户预收账户 |
| POST | `/prepayment-accounts/{id}:top-up` | 幂等预收充值 |
| POST | `/prepayment-accounts/{id}:apply` | 幂等抵扣账单 |
| POST | `/deposits` | 幂等收取押金 |
| POST | `/deposits/{id}:refund` | 幂等退还部分或全部押金 |
| POST | `/payment-transactions/{id}:reverse` | 冲正成功流水，不删除原流水 |
| GET | `/finance/transactions` | 按日期、渠道和类型查询正反向交易链 |
| GET | `/finance/balances` | 预收和押金账户、余额和流水数 |
| GET/POST/PUT | `/finance/discount-policies[/{id}]` | 查询、创建和按版本更新折扣策略 |
| GET/POST | `/finance/adjustments` | 查询或幂等申请账单调账 |
| POST | `/finance/adjustments/{id}:approve` | 按版本审批并原子更新账单金额 |
| POST | `/finance/adjustments/{id}:reject` | 按版本驳回并保留审批意见 |
| GET/POST | `/finance/receipt-segments` | 查询或新增受控收据号段 |
| GET | `/finance/receipts` | 查询原收据、状态和事件证据 |
| POST | `/finance/receipts/{id}:replace` | 换开新收据并关联原收据 |
| POST | `/finance/receipts/{id}:void` | 作废收据但不删除原快照 |
| POST | `/invoices:simulate` | 本地模拟首次开票 |
| GET | `/finance/invoices` | 查询首次、换开和红冲模拟发票链 |
| POST | `/finance/invoices/{id}:operate` | `REPLACE` 换开或 `RED` 负数红冲 |
| GET | `/finance/settlements:preview` | 按日试算交易数、收款、冲正、净额和渠道 |
| GET/POST | `/finance/settlements` | 查询或幂等关闭日结并归集交易 |
| POST | `/finance/settlements/{id}:lock` | 复核锁定日结及所属账单/交易 |
| GET | `/finance/reconciliation` | 返回账单、支付、预收、押金、日结、收据和发票七项差异 |

数据库与服务共同保证 `原应收 + 合法调整 = 账单总额 = 已付金额 + 未付金额`。成功财务记录没有物理删除接口；冲正、退还、换开、红冲和调账追加关联证据。当前支付和发票仍为显式本地模拟适配器。

## 仪表与计量

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/meter-workbench` | 返回仪表、批次、有效公摊版本、计量标准和异常/对账摘要 |
| GET | `/meters` | 查询项目内仪表主档、资产/父表、倍率/损耗和末次审核读数 |
| GET | `/meter-reading-batches` | 查询批次、来源、状态、正常/异常/已复核计数和聚合校验值 |
| GET | `/meter-reading-batches/{id}` | 查询批次、读数原快照、IoT Inbox 和计量对账明细 |
| POST | `/meter-reading-batches` | 以 `Idempotency-Key` 和请求哈希创建或重放抄表批次 |
| POST | `/meter-readings:input` | 批量录入连续读数；跨期/倒序拒绝，同批同表同值重放 |
| POST | `/meter-readings/{id}:review` | 按乐观版本复核异常并保存理由/经办；不覆盖原读数 |
| POST | `/meter-readings:import-simulated` | 从确定性 IoT 模拟器导入并保存 Inbox 负载和 SHA-256 |
| POST | `/meter-reading-batches/{id}:approve` | 阻断未复核异常，冻结读数并生成批次聚合校验值 |
| POST | `/meter-share-rules:preview` | 按账期有效策略版本试算面积公摊 |
| POST | `/meter-share-rules:apply` | 幂等保存版本化公摊结果并重算草稿读数快照 |
| POST | `/meters/{id}:replace` | 以 `Idempotency-Key` 校验旧止码连续性并原子保存新旧表及凭证 |
| POST | `/meter-reading-batches/{id}:generate-charges` | 仅对审核读数按有效计量标准/分配幂等生成账单明细 |
| GET | `/meter-reading-batches/{id}/reconciliation` | 查询 COUNT、USAGE、AMOUNT 三项计量源—账单差异 |

读数固定保存公式输入、倍率、损耗、修正、公摊版本和 SHA-256；计量账单再嵌入原读数快照与原校验值，规则变化不会回写历史。面积公摊和计量公式的响应/账单快照包含 `assumptionRule=true`，明确表示仍需真实业务口径替换。G5 通用周期应收不会处理 `METER_USAGE` 分配，计量类应收只能从已审核批次生成。

## 报表、导出、打印与通知

报表读取按定义所属领域要求 `finance:read`、`report:read`、`notification:read`、`invoice:read`、`bank:read` 或 `property:read`；导出/下载要求对应 `*:export`，收据打印要求 `finance:print`。任务列表只返回调用者具备读取权限的报表任务，不能凭任务 ID 越权读取元数据或制品。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/reports/catalog` | 返回当前用户可见的 22 个固定定义、路径、行粒度、公式、字段白名单和固定算例 |
| GET | `/reports/{reportCode}` | 按项目、固定筛选和服务端分页执行受控读模型，返回汇总、钻取、查询 SHA-256、数据源模式和耗时 |
| POST | `/report-jobs` | 以 `Idempotency-Key` 创建 CSV/XLSX/PDF/PRINT 异步任务；保存请求哈希、筛选、选择列和水印 |
| GET | `/report-jobs` | 按项目返回当前用户可读报表的任务、终态和制品元数据 |
| GET | `/report-jobs/{id}` | 读取单个任务及状态事件；按报表所属域重新授权 |
| GET | `/report-jobs/{id}/artifact` | 下载成功制品；要求对应导出权限并追加下载事件 |
| POST | `/receipt-print-jobs` | 批量冻结已签发收据快照并异步生成 PDF/PRINT 制品 |
| GET | `/receipt-print-jobs` | 查询项目内打印任务、模板版本、项数、制品和校验和 |
| GET | `/receipt-print-jobs/{id}/artifact` | 下载打印制品；要求 `finance:print` |
| POST | `/notification-batches` | 创建账单通知模拟批次；只接受 `*_SIMULATOR` 渠道并保存脱敏消息证据 |
| GET | `/notification-batches` | 查询模拟批次、成功/失败计数和消息明细 |

报表编码固定为：`TRANSACTION_SUMMARY`、`TRANSACTION_DETAILS`、`RECEIPT_BATCH_PRINT`、`PAYMENTS`、`ARREARS`、`BILL_NOTIFICATIONS`、`BILLS`、`COLLECTION_RATE`、`ARREARS_CLEARANCE_RATE`、`COMPREHENSIVE_QUERY`、`COLLECTION_CLEARANCE_SUMMARY`、`CHARGE_DETAILS`、`DISCOUNT_DETAILS`、`PREPAYMENTS`、`OWNERSHIP_TRANSFERS`、`REMINDERS`、`FEE_STATUS`、`INVOICE_STATISTICS`、`DEPOSITS`、`DAILY_SETTLEMENT_DETAILS`、`ADJUSTMENTS`、`BANK_TRUST`。

客户端不得把 `queryChecksum` 当成授权凭据；它只证明本次筛选、口径版本和结果摘要。当前通知、银行信托和发票统计中的外部结果为明确模拟/内部台账模式，不能据此宣称真实投递、托收或税控查询成功。
