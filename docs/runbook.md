# 本地运行与运维手册

## 前置环境

- Java 17、Maven 3.9；Node.js 22、npm；Docker Desktop / Compose。
- 端口默认：Web `5174`、API `8088`、MySQL `3307`、Redis `6379`。
- 复制 `.env.example` 为 `.env`，所有密码和 JWT 密钥只保存在本机；不要提交 `.env`。

## 推荐启动

```powershell
docker compose up -d mysql redis
```

后端（Windows 中文路径需要显式 UTF-8）：

```powershell
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8'
cd apps/pms-api
mvn clean verify
java '-Dfile.encoding=UTF-8' -jar target/pms-api-0.1.0-SNAPSHOT.jar
```

后端使用 `.env` 对应的 `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET`、本地管理员变量和 `LOGIN_MAX_FAILURES`/`LOGIN_WINDOW_MINUTES`/`LOGIN_LOCK_MINUTES` 登录保护参数。前端：

```powershell
cd apps/admin-web
npm ci
npm run dev
```

访问 `http://localhost:5174`。健康检查：`http://localhost:8088/actuator/health`。

## 完整 Compose

```powershell
docker compose config --quiet
docker compose build mysql api web
docker compose up -d
docker compose ps
```

若无法连接 `auth.docker.io`，镜像构建会在获取 Maven/Temurin/Node/Nginx 基础镜像时失败；可先使用宿主机 Java/Node 启动，恢复 Docker Hub 访问后重试。

## 测试与契约

```powershell
cd apps/pms-api
mvn clean verify

cd ../admin-web
npm audit
npm test
npm run build
$env:PMS_E2E_PASSWORD='<本地管理员密码>'
$env:PMS_E2E_USERNAME='<本地管理员账号>'
npm run test:e2e
npm run test:catalog-smoke
npm run test:catalog-visual
npm run api:check:live
```

`npm test` 会核对 49 页验收卡未漂移、提交版 OpenAPI 快照与 `src/api/generated.ts` 一致。`api:check:live` 还要求 API 已在 `8088` 运行，并确认运行中契约与两份提交物一致。只有后端契约发生受审变更时才运行 `npm run api:generate` 更新快照和类型，并在同一提交中审查差异。

`test:catalog-smoke` 覆盖 49/49 路由、标题、非占位内容和横向溢出；`test:catalog-visual` 比对三个目标视口的 147 张 Windows/Chrome 基线。首次建立或受审 UI 变更时使用 `npx playwright test e2e/page-catalog.visual.spec.ts --update-snapshots`，人工检查后必须再执行一次不带更新参数的纯比对。

Playwright 默认使用 1 个 worker。所有生命周期用例共用同一个持久验收后端，串行执行可以避免迁移批次、IAM 变更或应收任务在其他用例截图/查询期间产生瞬时数据污染；需要并行时必须先为每个 worker 提供独立数据库和独立管理员上下文，不能只调高 `--workers`。

G3 房产—客户主链路可独立重复验证：

```powershell
npx playwright test e2e/property-customer-lifecycle.spec.ts --repeat-each=2 --workers=1
```

测试会创建临时客户、完成产权转移及幂等重放、通过房产和客户两个工作台检查时间线，然后恢复执行前的全部 OWNER/CO_OWNER 关系并软停用临时客户。连续执行后，主项目有效客户资产关系应稳定为 403；若计数变化，不能更新验收证据，必须先修复清理逻辑。

G4 迁移中心主链路可独立重复验证：

```powershell
npx playwright test e2e/migration-center-lifecycle.spec.ts --repeat-each=2 --workers=1
```

测试从页面 17 载入“32 条合格 + 1 条错误”样本，依次执行 Raw 上传、错误隔离、审批、生产写入、对账和回滚。每次结束后 `migration_object_map.active`、未回滚 `migration_change_log` 和本批生产目标剩余数都必须为 0；Raw、Quarantine、状态事件和审计应继续保留作为证据。相同文件与映射版本会返回原批次，若需在回滚后重新演练，应生成新来源内容或提升映射版本。

G5 费用与应收主链路可独立验证：

```powershell
npx playwright test e2e/fee-receivable-lifecycle.spec.ts --workers=1
```

用例检查页面 8 定义财税/舍入列、页面 9 分配预览、页面 10 周期试算与异步任务、页面 11 临时费用试算与异步任务；两个任务详情都必须完成且不能出现“不一致”。运行库再次执行会使用相同请求键安全重放，不会重复生成周期账单。若任务返回 `PERIODIC_BILL_CONFIGURATION_CONFLICT`，表示同一资产/账期已有不同配置快照的周期账单；应选择明确的验收资产/账期，不能删除或覆盖历史账单来让测试通过。

G6 收银与不可变账务主链路可独立验证：

```powershell
npx playwright test e2e/financial-ledger-lifecycle.spec.ts --workers=1
```

用例执行部分/合并收款、幂等重放与同键异请求冲突、两个并发确认、冲正重放、预收充值/抵扣/冲正恢复、押金收退、调账审批、发票换开/红冲、收据换开、空日结锁定、14 个真实财务页面检查和七项对账。它会在持久验收库追加不可变流水和审计证据，不应通过删除历史来“清理”；视觉用例对财务 GET 使用固定合成样本，避免截图受依法保留流水和执行顺序影响。

G7 仪表、抄表与计量费用主链路可独立验证：

```powershell
npx playwright test e2e/meter-governance-lifecycle.spec.ts --workers=1
```

用例选择主项目内具备计量分配的启用合成分表，执行批次幂等/冲突、跨期拒绝、自动承接、重复读数、异常阻断、版本化公摊、复核、审核、计量账单和 COUNT/USAGE/AMOUNT 三项对账；另验证 IoT 模拟 Inbox 重放和临时合成表的换表凭证。每次运行会追加唯一命名的合成批次、读数、账单、Inbox、换表和事件证据，不要删除历史来恢复种子计数。视觉用例对计量工作台和看板 GET 使用固定合成样本，真实连续性/快照/对账仍由本用例和 MySQL 集成测试覆盖。

G8 报表、打印和通知主链路可独立验证：

```powershell
npx playwright test e2e/report-governance-lifecycle.spec.ts --workers=1
```

用例先在线执行全部 22 个固定报表并核对口径、查询 SHA-256、分页和项目必填错误，再核对看板收缴指标同源；随后从页面创建 XLSX 异步导出并校验 ZIP 文件头和 SHA-256，选择已签发收据执行批量打印并核对次数，最后发送脱敏的 SMS 模拟通知并确认银行信托仍为模拟状态。每次运行会追加唯一任务和不可变事件，不要删除历史；视觉用例固定报表 GET/任务列表，真实任务由该用例和 MySQL 集成测试覆盖。

G9 外部集成治理主链路可独立验证：

```powershell
npx playwright test e2e/integration-governance-lifecycle.spec.ts --workers=1
```

运行前还需把本地忽略的回调签名秘密注入 `PMS_E2E_CALLBACK_SECRET`，不得写入源码或命令历史。用例确认五项策略全部非生产、连接测试可追溯、无签名回调被拒绝、正确签名回调可处理且同负载安全重放；随后制造三次可重试失败、进入死信、重放成功，并在页面检查投递和恢复证据。后端集成测试另验证同回调编号不同负载冲突、项目隔离，以及支付/应收业务 Outbox 不能被模拟投递或排空。

G9 供应链、恢复和空库门禁：

```powershell
pwsh -File scripts/generate-sbom.ps1
pwsh -File scripts/scan-tracked-secrets.ps1
pwsh -File scripts/scan-container-images.ps1
pwsh -File scripts/restore-drill.ps1
pwsh -File scripts/empty-stack-drill.ps1
```

制品进入忽略的 `.artifacts`。镜像扫描固定检查 API、Web、加固版 MySQL 8.4 和 Redis 的 Critical/High；恢复脚本只使用随机临时库；空库脚本只使用带精确 project 标签的隔离容器/网络/卷，并在验证 `19|88|22|5|0`、API readiness 和 Web 200 后自动清理。

## 数据库迁移和备份

- 应用启动时自动执行 Flyway；生产环境禁止修改已执行的迁移文件，只能新增版本。
- 升级前使用 `mysqldump --single-transaction` 备份业务库，另行备份 `.env` 中的秘密到受控密码库。
- 回滚代码前确认新迁移是否向后兼容；财务数据不得以删除迁移方式回退。
- 恢复后必须检查 Flyway 状态、账单恒等式、孤儿外键、登录、仪表和支付模拟链路。
- G9 恢复检查至少包括：Flyway 为 V19、空库 88 张表、22 个 ACTIVE 报表、5 个适配器策略且 production_ready=0；主项目有效房屋/车位/客户/客户资产关系为 359/250/403/403、隔离项目具备 1/1/1/2/1 的网格/楼栋/单元/资产/客户链；费用定义/标准/分配为 23/17/773（其中仪表分配 30），收据号段 2、合成折扣策略 1、公摊规则版本 1；迁移/档案/费用/财务/计量既有差异均为 0。还需核对无 QUEUED/RUNNING/FAILED 报表或打印任务、任务/制品/事件/打印快照/通知正文 SHA-256 合法、打印项数/收据次数/通知计数一致、真实通知通道为 0；集成回调/尝试 SHA-256 异常为 0、开放死信和待排空模拟 Outbox 为 0，业务 Outbox 不纳入模拟排空。

## 故障定位

- 每个响应带 `X-Request-Id`；错误响应和后端日志可按同一 ID 对齐。
- `401`：令牌缺失、过期或已由会话版本撤销；`403`：权限/项目范围不足，或首次改密前访问业务接口；`429`：账号/IP 登录失败达到阈值；`409`：版本冲突或幂等业务冲突；`400`：字段/业务规则错误。
- MySQL/Redis 先查看 `docker compose ps` 健康状态，再检查端口占用和本地 `.env`。
- Playwright 直接使用本机 Chrome；失败产物位于 `apps/admin-web/test-results` 和 `playwright-report`。
- G1 固定视觉门禁使用 `npm run test:visual`，覆盖 1366×768、1440×900、1920×1080 的登录页与看板；G2—G9 使用 `test:catalog-smoke` 和 `test:catalog-visual` 覆盖 49 页。视觉测试把看板、迁移批次、应收任务、G6 财务、G7 计量、G8 报表/任务和 G9 集成治理查询固定为确定性合成响应，防止依法保留的审计、账务、计量和任务证据导致截图漂移；真实 API 生命周期仍由专用 E2E 和后端集成测试覆盖。原因、人工检查和摘要见 `visual-baselines.md`。
