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

后端使用 `.env` 对应的 `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET` 和本地管理员变量。前端：

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

当前机器若无法连接 `auth.docker.io`，镜像构建会在获取 Maven/Temurin/Node/Nginx 基础镜像时失败；这属于外部网络条件，不影响宿主机 Java/Node 启动。恢复 Docker Hub 访问后重试即可。

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
npm run api:generate
```

`api:generate` 要求 API 已在 `8088` 运行，并更新 `src/api/generated.ts`。

## 数据库迁移和备份

- 应用启动时自动执行 Flyway；生产环境禁止修改已执行的迁移文件，只能新增版本。
- 升级前使用 `mysqldump --single-transaction` 备份业务库，另行备份 `.env` 中的秘密到受控密码库。
- 回滚代码前确认新迁移是否向后兼容；财务数据不得以删除迁移方式回退。
- 恢复后必须检查 Flyway 状态、账单恒等式、孤儿外键、登录、仪表和支付模拟链路。

## 故障定位

- 每个响应带 `X-Request-Id`；错误响应和后端日志可按同一 ID 对齐。
- `401`：令牌缺失/过期；`403`：权限或项目范围不足；`409`：版本冲突或幂等业务冲突；`400`：字段/业务规则错误。
- MySQL/Redis 先查看 `docker compose ps` 健康状态，再检查端口占用和本地 `.env`。
- Playwright 直接使用本机 Chrome；失败产物位于 `apps/admin-web/test-results` 和 `playwright-report`。
