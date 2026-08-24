# API 参考

## 通用约定

- 基础路径：`/api/v1`；OpenAPI：`/v3/api-docs`；Swagger UI：`/docs`。
- 除登录、健康检查和 OpenAPI 外，所有请求使用 `Authorization: Bearer <JWT>`。
- 前端为每个请求生成 `X-Request-Id`；后端会校验并回写该响应头，缺失或不安全时生成 UUID。
- 写接口先校验 RBAC 权限，再校验 `communityId` 是否在登录人的项目范围内。
- 金额和用量在 JSON 中以字符串输出，后端使用 `BigDecimal`，数据库使用 `DECIMAL`。
- 幂等写操作使用 `Idempotency-Key`。相同业务和相同键返回已存在结果，不重复落账。
- 错误结构包含 `code`、`message`、`requestId`、`timestamp` 和可选 `violations`。

## 认证和平台

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | 本地账号登录，返回 JWT、角色、权限和项目范围 |
| GET | `/auth/me` | 当前用户与权限快照 |
| GET | `/runtime` | 运行模式和适配器状态，不返回秘密 |
| GET | `/dashboard` | 项目统计、收费概览和数据质量 |

## 通用数据接口

`GET /data/{resource}` 支持 `communityId`、`keyword`、`status`、`category`、`page`、`size` 和白名单 `sort`。返回 `items`、`page`、`size`、`total`、`totalPages`。

高置信档案资源支持：

- `POST /data/{resource}`；
- `PUT /data/{resource}/{id}?version=n`（乐观锁）；
- `DELETE /data/{resource}/{id}?version=n`（软停用，不物理删除）。

资源包括小区、楼栋、单元、资产、客户、客户资产关系、车辆、仪表、费用定义/标准/版本/分配、账单、支付订单/流水、预收、押金、收据、抄表批次/读数、公摊、换表、字典、用户和审计等。

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
