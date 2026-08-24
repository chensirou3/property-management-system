# 数据字典与不变量

## 全局约定

- 主键使用 UUID 字符串；合成种子使用可识别但全新生成的固定 UUID。
- 时间统一以 UTC 保存，主要业务表保留 `created_at`、`updated_at`。
- 可修改聚合保留 `version` 做乐观锁；业务停用使用状态字段，不直接删除。
- 金额为 `DECIMAL(18,2)`，用量、单价、系数和面积保留更高小数位。
- JSON 快照用于保存公式、收据、适配器和计算上下文，避免未来规则变化改写历史。

## 表分组（空库迁移后共 50 张业务/基础设施表）

| 分组 | 核心表 | 作用 |
|---|---|---|
| IAM | `app_user`、`role`、`permission`、用户角色/角色权限/用户项目关系 | 登录、RBAC、项目数据范围 |
| 档案 | `community`、`building`、`building_unit`、`asset`、房屋/车位明细 | 项目和空间资产主数据 |
| 客户车辆 | `customer`、`customer_asset_relation`、`vehicle`、`vehicle_parking_relation` | 客户、资产权属/租住、车辆和车位关系 |
| 费用 | `fee_definition`、`fee_standard`、`fee_standard_version`、`fee_allocation` | 费用项目、版本化标准和资产分配 |
| 应收 | `receivable_job`、`bill`、`bill_item` | 试算/生成任务、账单和不可变计算快照 |
| 收款 | `payment_order`、`payment_order_intent`、`payment_transaction`、`payment_allocation` | 支付意图、确认流水和账单分配 |
| 预收押金 | `prepayment_account`、`prepayment_transaction`、`deposit`、`deposit_transaction` | 余额账户与完整变动流水 |
| 票据 | `receipt`、`invoice_request` | 收据快照和模拟发票结果 |
| 仪表 | `meter`、`meter_reading_batch`、`meter_reading`、`meter_share_rule`、`meter_share_result`、`meter_replacement` | 表具、抄表、公摊和换表 |
| 支撑 | `audit_log`、`outbox_event`、`system_dictionary`、导入/迁移任务表 | 审计、事件外盒、字典和作业状态 |

## 关键不变量

1. 同一项目、资产和账期最多一张账单。
2. `bill.total_amount = bill.paid_amount + bill.outstanding_amount`，三个值均不得为负。
3. 每条计量读数最多生成一次 `bill_item`，由 `(source_type, source_id)` 唯一约束保证。
4. 同一抄表批次中每个仪表最多一条读数。
5. 同一批次、规则和资产最多一条公摊结果。
6. 费用标准版本独立保存；历史账单引用标准版本和计算快照。
7. 支付、预收、押金写操作按业务对象和幂等键唯一。
8. 成功支付通过冲正流水回退，不物理删除。

## 合成优山美地基线

| 对象 | 数量 |
|---|---:|
| 项目 / 楼栋 / 单元 | 1 / 8 / 4 |
| 房屋 / 车位 | 359 / 250 |
| 客户 / 客户资产关系 | 403 / 403 |
| 车辆 | 30 |
| 仪表 | 31（1 总表 + 30 分表） |
| 费用定义 / 初始标准 | 22 / 16 |
| 房屋费用分配 / 车位费用分配 | 549 / 194 |
| 演示账单 | 20 |

全新数据库验收结果：7 个 Flyway 迁移成功、50 张表、359 套房屋、403 个客户、743 条费用分配；账单恒等式违规和客户资产孤儿关系均为 0。
