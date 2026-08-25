# 架构决策

## ADR-001：独立工程

复刻工程不修改 `物业核心产品源码`。Java110 用作领域和适配参考，新代码、迁移和测试均保存在本目录。

## ADR-002：模块化单体

第一版使用一个 Spring Boot 进程，按 `iam`、`property`、`fee`、`cashier`、`meter`、`adapter` 分包。跨模块通过应用服务和领域事件协作，避免初期微服务部署成本。

## ADR-003：稳定 API

前端只依赖 `/api/v1`。目标站相对 `/list` 接口和 Java110 `serviceCode` 不进入页面代码；兼容逻辑放在 adapter 层。

## ADR-004：财务不可变

成功账单和交易不物理删除。退款、冲正、预收抵扣和押金退还使用关联反向流水。金额使用 `BigDecimal` 和数据库 Decimal。

## ADR-005：外部通道默认模拟

支付、发票、IoT 和 Java110 都定义端口接口。开发环境启用确定性模拟实现，页面必须展示“模拟通道”，不得描述为生产能力。

## ADR-006：合成数据

种子数据只复现“优山美地”的规模、字段覆盖和关系统计。个人信息、原始 ID、房号、金额和业务凭据全部重新生成。

## ADR-007：Spring Boot 受支持版本

API 已从 Spring Boot 2.7.18 迁移到 Spring Boot 3.5.16，保持 Java 17，并完成 Jakarta、Spring Security 6、springdoc 2.9 与 Flyway/MySQL 兼容迁移。详细决策、验收和回滚见 [`adr/ADR-007-spring-boot-supported-version.md`](adr/ADR-007-spring-boot-supported-version.md)。

## ADR-008：统一资产与不可变关系事件

房屋、车位和公共区域统一使用 `asset` 聚合，类型专属字段放入一对一明细表；网格—楼栋—单元—资产通过带 `community_id` 的复合外键保证项目内层级。客户资产关系使用有效期、状态和版本表达权属/租住变化，不覆盖历史；关系起止和产权转移另写入 `property_relation_event`，请求键保证幂等。档案停用使用引用保护和软状态，不通过级联删除清除业务历史。

## ADR-009：受治理的五层迁移与反向变更日志

迁移中心以批次为边界，把来源证据依次分为 Raw、Quarantine、Canonical、Staging 和 Production。来源 SHA-256 与映射版本形成重放键；Raw 保留原始 JSON 和逐行校验和，错误行与合格行物理分层。生产写入必须经过项目范围、权限、平台管理员审批和乐观版本校验。首批只允许 PROJECT、BUILDING、ROOM ASSET、CUSTOMER、RELATION 白名单资源，不执行任意表名或 SQL。每个新建对象及房屋类型明细写入反向变更日志，回滚按执行顺序倒序删除本批对象，同时保留 Raw、隔离错误、映射、对账、事件与审计证据。
