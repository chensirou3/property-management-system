# 数据字典与不变量

## 全局约定

- 主键使用 UUID 字符串；合成种子使用可识别但全新生成的固定 UUID。
- 时间统一以 UTC 保存，主要业务表保留 `created_at`、`updated_at`。
- 可修改聚合保留 `version` 做乐观锁；业务停用使用状态字段，不直接删除。
- 金额为 `DECIMAL(18,2)`，用量、单价、系数和面积保留更高小数位。
- JSON 快照用于保存公式、收据、适配器和计算上下文，避免未来规则变化改写历史。

## 表分组（空库迁移后共 73 张业务/基础设施表）

| 分组 | 核心表 | 作用 |
|---|---|---|
| IAM | `sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`sys_user_project_scope`、`auth_login_guard` | 登录、RBAC、项目数据范围、会话版本与登录限流 |
| 企业组织 | `enterprise`、`organization_unit`、`org_position`、`employee` | 企业、组织树、岗位、人员及其项目归属 |
| 档案 | `community`、`grid_area`、`building`、`pms_unit`、`asset`、`room_detail`、`parking_space_detail` | 项目、网格、楼栋、单元和统一空间资产主数据 |
| 客户车辆 | `customer`、`customer_asset_relation`、`property_relation_event`、`vehicle`、`vehicle_parking_relation` | 客户、资产权属/租住、不可变关系事件、车辆和车位关系 |
| 费用 | `fee_definition`、`fee_standard`、`fee_standard_version`、`fee_allocation`、`fee_configuration_event` | 会计/税务定义、版本化标准、资产/仪表分配和配置变更证据 |
| 应收 | `receivable_generation_job`、`receivable_generation_item`、`receivable_generation_error`、`receivable_generation_reconciliation`、`bill`、`bill_item` | 周期/临时异步任务、逐行状态/错误/对账、账单和不可变计算快照 |
| 收款 | `payment_order`、`payment_order_intent`、`payment_transaction`、`payment_allocation`、`cashier_shift` | 支付意图、确认流水、账单分配和收银交班 |
| 预收押金 | `prepayment_account`、`prepayment_transaction`、`deposit`、`deposit_transaction` | 余额账户与完整变动流水 |
| 票据 | `receipt`、`receipt_number_segment`、`invoice_request` | 受控号段、收据快照、换开/作废证据和模拟发票结果 |
| 财务治理 | `bill_adjustment`、`discount_policy`、`daily_settlement`、`financial_event` | 调账审批、折扣策略、日结锁定和统一不可变事件链 |
| 仪表 | `meter`、`meter_reading_batch`、`meter_reading`、`meter_share_rule`、`meter_share_result`、`meter_replacement` | 表具、抄表、公摊和换表 |
| 迁移治理 | `migration_batch`、`migration_raw_record`、`migration_quarantine_record`、`migration_canonical_record`、`migration_staging_record`、`migration_object_map`、`migration_reconciliation`、`migration_change_log`、`migration_batch_event` | 五层证据、源目标映射、审批执行、对账、状态轨迹与逆序回滚 |
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
9. 网格、楼栋、单元、资产、客户、车辆、仪表和各类关系必须处于同一 `community_id`；复合外键拒绝跨项目引用。
10. `asset.usable_area` 不得大于 `building_area`，生效结束日不得早于开始日；房屋/车位创建时必须同时创建对应类型明细。
11. 同一客户、资产、关系类型最多存在一条有效关系；同一车辆、车位最多存在一条有效关系，生成列与唯一键从数据库层阻止重复。
12. 产权变更会结束资产上全部有效 `OWNER`/`CO_OWNER`，创建新的 `OWNER`，并在 `property_relation_event` 保存请求键、操作者、原因和前后关系快照。
13. 有下级、有效关系、车辆、仪表或其他业务引用的档案不能直接停用；停用是状态变更，历史行不物理删除。
14. 迁移 Raw 记录在服务边界不可变；错误记录只进入 Quarantine，只有已审批的有效 Staging 记录可以写入 Production。
15. `(community_id, source_sha256, mapping_version)` 唯一保证同一来源安全重放；生产新增对象逐条记录 `migration_change_log`，回滚只按逆依赖删除本批创建对象并保留全部迁移证据。
16. 同一费用标准的 ACTIVE 版本生效区间不得重叠；同一标准、目标和日期范围的 ACTIVE 分配不得重叠。
17. 费用分配取消通过设置 `effective_to` 和取消原因保留历史；历史账单继续引用原 `fee_standard_version_id`、`fee_allocation_id` 和 JSON 快照。
18. 应收任务的 `(community_id, request_key)` 唯一，且保存请求 SHA-256；同键同请求重放，同键不同请求拒绝。
19. 周期账单生成键为项目、资产和账期，数据库唯一约束禁止重复；临时应收不占用周期键。
20. 每个应收任务保存逐行结果及 `ELIGIBLE_ITEM_COUNT`、`GENERATED_BILL_COUNT`、`TOTAL_AMOUNT` 三项对账；COMPLETED 任务必须全部 `MATCHED`、差异为 0。
21. 账单金额使用 `original_amount + adjustment_amount = total_amount`，并继续满足 `total_amount = paid_amount + outstanding_amount`；已日结锁定账单不得直接改写或冲正。
22. 支付订单、班次、日结、预收和押金幂等写同时保存请求键与请求 SHA-256；同键不同请求必须冲突，同键同请求只返回原结果。
23. 同一支付订单最多一条成功 PAYMENT；确认并发由订单行锁和唯一成功键共同保护，收据只签发一次。
24. 冲正、预收抵扣回退、押金退款、调账、收据换开/作废和发票换开/红冲均追加关联记录，不删除原成功事实。
25. 收据和发票保存最终 JSON 快照 SHA-256；日结保存交易数、收款、冲正、净额和渠道快照，锁定后所属交易不可直接冲正。

## 合成项目基线

| 对象 | 数量 |
|---|---:|
| 项目（全库） | 2（优山美地主项目 + 海湾雅居隔离项目） |
| 优山美地网格 / 楼栋 / 单元 | 0 / 8 / 4 |
| 海湾雅居网格 / 楼栋 / 单元 | 1 / 1 / 1 |
| 企业 / 组织单元 / 岗位 | 1 / 3 / 2 |
| 优山美地有效房屋 / 车位 | 359 / 250 |
| 全库有效房屋 / 车位 | 360 / 251 |
| 优山美地有效客户 / 有效客户资产关系 | 403 / 403 |
| 全库有效客户 / 有效客户资产关系 | 404 / 404 |
| 车辆 | 31（主项目 30 + 隔离项目 1） |
| 仪表 | 32（主项目 31 + 隔离项目 1） |
| 费用定义 / 初始标准 | 23 / 16（其中 1 个为明确标识的临时费用合成示范） |
| 房屋费用分配 / 车位费用分配 | 549 / 194 |
| 演示账单 | 20 |

全新数据库验收结果：15 个 Flyway 迁移成功、73 张表、65 个权限、2 个有效项目；主项目为 359 套有效房屋、250 个有效车位、403 个有效客户和 403 条有效客户资产关系，隔离项目具备最小完整档案链。G4 的 32 条合格 + 1 条错误样本可重复完成隔离、审批、31 条生产写入 + 1 条项目映射、9 项对账和 41 条变更逆序回滚。G5 持久运行库为 23 个费用定义、16 个标准、743 条分配。G6 增加 2 个收据号段和 1 个合成折扣策略；账单、支付、预收、押金、日结、收据和发票七项对账差异均为 0。客户资产孤儿关系、跨项目关系、重复有效关系和非法面积均为 0。开发运行库允许保留已停用、已回滚或已冲正的 E2E 历史证据，因此阶段对账以有效状态和不可变账本守恒值为准。
