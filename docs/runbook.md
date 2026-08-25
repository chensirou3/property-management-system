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
docker compose build api web
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
npm run test:e2e
npm run test:catalog-smoke
npm run test:catalog-visual
npm run api:check:live
```

`npm test` 会核对 49 页验收卡未漂移、提交版 OpenAPI 快照与 `src/api/generated.ts` 一致。`api:check:live` 还要求 API 已在 `8088` 运行，并确认运行中契约与两份提交物一致。只有后端契约发生受审变更时才运行 `npm run api:generate` 更新快照和类型，并在同一提交中审查差异。

`test:catalog-smoke` 覆盖 49/49 路由、标题、非占位内容和横向溢出；`test:catalog-visual` 比对三个目标视口的 147 张 Windows/Chrome 基线。首次建立或受审 UI 变更时使用 `npx playwright test e2e/page-catalog.visual.spec.ts --update-snapshots`，人工检查后必须再执行一次不带更新参数的纯比对。

## 数据库迁移和备份

- 应用启动时自动执行 Flyway；生产环境禁止修改已执行的迁移文件，只能新增版本。
- 升级前使用 `mysqldump --single-transaction` 备份业务库，另行备份 `.env` 中的秘密到受控密码库。
- 回滚代码前确认新迁移是否向后兼容；财务数据不得以删除迁移方式回退。
- 恢复后必须检查 Flyway 状态、账单恒等式、孤儿外键、登录、仪表和支付模拟链路。

## 故障定位

- 每个响应带 `X-Request-Id`；错误响应和后端日志可按同一 ID 对齐。
- `401`：令牌缺失、过期或已由会话版本撤销；`403`：权限/项目范围不足，或首次改密前访问业务接口；`429`：账号/IP 登录失败达到阈值；`409`：版本冲突或幂等业务冲突；`400`：字段/业务规则错误。
- MySQL/Redis 先查看 `docker compose ps` 健康状态，再检查端口占用和本地 `.env`。
- Playwright 直接使用本机 Chrome；失败产物位于 `apps/admin-web/test-results` 和 `playwright-report`。
- G1 固定视觉门禁使用 `npm run test:visual`，覆盖 1366×768、1440×900、1920×1080 的登录页与看板；G2 使用 `test:catalog-smoke` 和 `test:catalog-visual` 覆盖 49 页。基线清单、摘要和受控更新步骤见 `visual-baselines.md`。
