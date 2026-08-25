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

关系写入需要 `Idempotency-Key`；同一项目和请求键重放返回原结果。数据库生成列禁止重复有效关系，乐观锁防止静默覆盖；类型化响应不会返回手机、证件原文。导入预校验只检查模板、必填项、项目引用、重复编码、面积、日期和关系重叠，不会实际写库；批量落库、断点续跑和对账属于 G4 迁移中心。

## 费用

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/fee-standards` | 创建标准及第一个不可变版本 |
| POST | `/fee-allocations:batch-assign` | 批量分配资产，重复分配跳过 |
| POST | `/fee-allocations:batch-cancel` | 批量软停用分配 |
| POST | `/receivable-jobs:preview` | 只计算不落账，返回公式快照 |
| POST | `/receivable-jobs` | 幂等生成账单和明细 |

## 收银和财务

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/cashier/context` | 按客户、资产、账单号联合检索待收账单 |
| POST | `/payment-orders` | 幂等创建支付意图，锁定账单余额 |
| POST | `/payment-orders/{id}:confirm-simulated` | 通过本地模拟器确认，生成流水、分配和收据 |
| POST | `/prepayment-accounts` | 获取或创建客户预收账户 |
| POST | `/prepayment-accounts/{id}:top-up` | 幂等预收充值 |
| POST | `/prepayment-accounts/{id}:apply` | 幂等抵扣账单 |
| POST | `/deposits` | 幂等收取押金 |
| POST | `/deposits/{id}:refund` | 幂等退还部分或全部押金 |
| POST | `/payment-transactions/{id}:reverse` | 冲正成功流水，不删除原流水 |
| POST | `/invoices:simulate` | 本地模拟开票 |

数据库约束保证 `账单总额 = 已付金额 + 未付金额`。成功财务记录没有物理删除接口。

## 仪表与计量

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/meter-reading-batches` | 创建或重放抄表批次 |
| POST | `/meter-readings:input` | 批量录入读数；同批同表同值重放 |
| POST | `/meter-readings:import-simulated` | 从确定性 IoT 模拟器导入 |
| POST | `/meter-reading-batches/{id}:approve` | 审核批次和读数 |
| POST | `/meter-share-rules:preview` | 按演示面积比例试算公摊 |
| POST | `/meter-share-rules:apply` | 幂等保存 359 户公摊结果并更新草稿读数 |
| POST | `/meters/{id}:replace` | 原子更新旧表、新表和换表记录 |
| POST | `/meter-reading-batches/{id}:generate-charges` | 对审核读数幂等生成计量账单明细 |

公摊和计量公式的响应快照包含 `assumptionRule=true`，明确表示仍需真实业务口径替换。
