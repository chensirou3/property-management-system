# 数据字典与不变量

## 全局约定

- 主键使用 UUID 字符串；合成种子使用可识别但全新生成的固定 UUID。
- 时间统一以 UTC 保存，主要业务表保留 `created_at`、`updated_at`。
- 可修改聚合保留 `version` 做乐观锁；业务停用使用状态字段，不直接删除。
- 金额为 `DECIMAL(18,2)`，用量、单价、系数和面积保留更高小数位。
- JSON 快照用于保存公式、收据、适配器和计算上下文，避免未来规则变化改写历史。

## 表分组（空库迁移后共 88 张业务/基础设施表）

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
| 仪表 | `meter`、`meter_reading_batch`、`meter_reading`、`meter_share_rule`、`meter_share_rule_version`、`meter_share_result`、`meter_replacement`、`iot_reading_inbox`、`meter_charge_reconciliation`、`meter_event` | 表具、连续读数、版本化公摊、IoT 入站、换表凭证、计量对账和事件链 |
| 报表与通知 | `report_definition`、`report_export_job`、`report_export_event`、`receipt_print_job`、`receipt_print_item`、`notification_batch`、`notification_message` | 固定报表口径、异步制品/事件、打印快照/次数和模拟通知证据 |
| 外部集成治理 | `integration_adapter_policy`、`integration_callback_inbox`、`integration_delivery_attempt`、`integration_dead_letter` | 适配器 fail-closed 策略、签名回调幂等、连接/入站/出站尝试和死信重放证据 |
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
26. 抄表批次的 `(community_id, request_key)` 唯一并保存请求 SHA-256；账期必须晚于仪表最后审核账期，读数时间必须属于批次账期。
27. 读数保存倍率、损耗、修正、公摊、原输入 JSON 和 SHA-256；异常必须复核才能审核，审核批次冻结读数和聚合校验值。
28. 公摊结果必须引用账期有效的规则版本；同一规则、批次和资产参数相同可重放，参数不同必须冲突。
29. IoT 模拟入站按适配器和来源引用唯一，保留原负载及 SHA-256；换表按幂等键唯一并保存新旧版本、连续读数、凭证号和快照校验值。
30. `METER_USAGE` 分配只允许经已审核计量批次生成应收；账单明细嵌入原读数快照/校验值，每条读数最多计费一次，并保存 COUNT/USAGE/AMOUNT 三项对账。
31. `report_definition.report_code` 和 `page_path` 分别唯一；报表只执行代码内固定查询与字段白名单，不接受任意 SQL、表名或列名。
32. 报表导出任务的 `(community_id, request_key)` 唯一并保存请求 SHA-256；成功任务必须同时具备制品、MIME、行数、64 位制品校验和和完整状态事件。
33. 打印任务的 `item_count` 必须等于 `receipt_print_item` 数量；每个明细冻结收据快照和 SHA-256，成功任务数与 `receipt.print_count` 一致。
34. 通知批次渠道仅允许 `SMS_SIMULATOR`、`WECHAT_SIMULATOR`、`EMAIL_SIMULATOR` 且 `simulated=true`；成功/失败计数必须等于消息明细，正文快照和模拟引用不可为空。
35. `integration_adapter_policy.mode` 只允许 `SIMULATOR`/`DISABLED`；当前四个启用模拟器和一个禁用适配器的 `production_ready` 必须为 false，未实现生产模式在启动时 fail-closed。
36. 回调 `(adapter_code, callback_id)` 唯一并保存项目、负载 SHA-256 和追踪号；同负载只增加重放次数，不同负载必须冲突，验签失败不得进入 Inbox。
37. 每次连接、入站和出站尝试按适配器、引用和尝试序号唯一并保存详情 SHA-256；重试耗尽后死信唯一，重放保留原尝试/死信并追加恢复证据。
38. 集成治理模拟投递只允许项目内 `aggregate_type=INTEGRATION_SIMULATOR` 且负载 `simulated=true` 的 Outbox；业务支付/应收事件不得由模拟单投递或排空接口消费。

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
| 费用定义 / 初始标准 | 23 / 17（含临时费用和 G7 计量水费合成示范） |
| 房屋费用分配 / 车位费用分配 | 549 / 194 |
| 仪表费用分配 | 30 |
| 演示账单 | 20 |
| 集成适配器策略 | 5（4 个 SIMULATOR + 1 个 DISABLED，生产就绪 0） |

全新数据库验收结果：19 个 Flyway 迁移成功、88 张表、65 个权限、22 个 ACTIVE 报表、5 个 fail-closed 适配器策略和 2 个有效项目；主项目为 359 套有效房屋、250 个有效车位、403 个有效客户和 403 条有效客户资产关系，隔离项目具备最小完整档案链。G4 的 32 条合格 + 1 条错误样本可重复完成隔离、审批、31 条生产写入 + 1 条项目映射、9 项对账和 41 条变更逆序回滚。G5/G7 合成种子为 23 个费用定义、17 个标准、773 条分配，其中 30 条为仪表计量分配。G6 增加 2 个收据号段和 1 个合成折扣策略；七项财务对账差异为 0。G7 增加 1 个公摊规则版本和计量读数/IoT/换表/计费证据，COUNT/USAGE/AMOUNT 三项对账差异为 0。G8 固定 22 个报表定义；G9 固定四个模拟器和一个禁用适配器，生产就绪为 0。客户资产孤儿关系、跨项目关系、重复有效关系和非法面积均为 0。开发运行库允许保留已停用、已回滚、已冲正或由生命周期追加的 E2E 历史证据，因此阶段对账以空库种子、有效状态和不可变账本守恒值为准。
