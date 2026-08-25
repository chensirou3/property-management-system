# G9 部署、安全、可观测与恢复基线

更新时间：2026-08-25

## 1. 能力边界

本工程当前只具备内部可验收的模拟集成边界：支付、发票、银行信托和 IoT 为 `SIMULATOR`，Java110 为 `DISABLED`，五个策略的 `production_ready` 均为 `false`。没有支付机构、税控、银行、设备厂商或 Java110 生产授权时，不得把连接测试、模拟投递或回调演练描述为生产接入。

第三方治理台只展示脱敏端点、凭据状态和校验值；不会读取或回显任何密钥。模式切换和密钥变更必须在部署系统完成，不能在浏览器中修改。

## 2. HTTPS 边界

生产入口必须使用可信 CA 证书和 TLS 1.2/1.3。参考配置为 `deploy/nginx-tls.conf.example`，上线前至少替换域名、证书 Secret 路径和上游网络：

- HTTP 全量重定向 HTTPS，并启用 HSTS；
- 登录按 IP 限制为 5 次/分钟，服务端仍保留账号失败锁定；
- 回调入口限制为 30 次/分钟、1 MB 请求体，并继续执行 HMAC、时间窗和幂等校验；
- `/actuator`、`/docs`、`/swagger-ui` 和 `/v3/api-docs` 不向公网开放；探针通过容器网络或编排平台读取；
- 入口生成并透传 `X-Request-Id`，后端日志、审计和回调证据使用同一追踪号。

## 3. Secret 管理

应用已启用 `optional:configtree:/run/secrets/`。`deploy/docker-compose.production.example.yml` 展示 API 容器的 Secret 引用方式，但只是生产覆盖示例，不是可直接上线的环境定义。

必须分别生成和托管以下秘密，禁止提交 Git、写入镜像、放入截图或日志：

| Secret | 最低要求 | 轮换影响 |
|---|---|---|
| `JWT_SECRET` | 独立随机值，至少 32 字节 | 现有访问令牌失效 |
| `PMS_CALLBACK_SIGNING_SECRET` | 与 JWT 不同，至少 32 字节 | 需与回调方双边切换 |
| `MYSQL_PASSWORD` | 专用应用账号随机密码 | 更新连接池后重启 API |
| `REDIS_PASSWORD` | 专用随机密码或托管 ACL | 更新连接后重启 API |
| `PMS_BOOTSTRAP_ADMIN_PASSWORD` | 首次初始化临时值 | 初始化后立即轮换并受控保存 |

开发机 `.env` 只允许保留在忽略文件中。生产应使用 Docker/Kubernetes Secret、云 Secret Manager 或等价受控系统；Secret 的读取权限仅授予对应工作负载身份。

## 4. 最小权限

- Web 容器只访问 API，不访问 MySQL、Redis 或宿主 Docker socket。
- API 只访问指定 MySQL schema、Redis 命名空间和必要的模拟/生产适配器网络。
- 数据库迁移账号与运行账号应在正式部署中拆分：迁移账号临时具有 DDL 权限；运行账号只保留目标 schema 的 `SELECT/INSERT/UPDATE/EXECUTE`。当前单容器自动迁移方式仅用于内部验收。
- 任何生产回调出口使用域名白名单、固定 CA 信任和最短可用超时；禁止任意 URL 由页面输入。
- 平台管理员才能创建模拟 Outbox、制造故障和回放死信；普通 `integration:read` 用户只能查看脱敏证据。

## 5. 签名、幂等和投递

回调 canonical string 固定为：

```text
timestamp + "\n" + adapterCode + "\n" + callbackId + "\n" + rawJsonPayload
```

签名算法为 HMAC-SHA256，十六进制小写输出。服务端执行恒定时间比较、默认 ±300 秒时间窗、1 MB 限制，以及 `(adapter_code, callback_id)` 唯一约束。同一回调编号和相同负载只增加 `replay_count`；相同编号携带不同负载返回 `409 CALLBACK_REPLAY_CONFLICT`。

Outbox 的负载和每次投递详情分别保存 SHA-256。模拟适配器最多重试三次，按策略延迟后进入死信；回放不会删除原死信或尝试记录，成功后将死信置为 `RESOLVED`。生产适配器仍属于外部门禁，不能通过更改数据库字段绕过 fail-closed 启动校验。

## 6. 日志、指标与告警

容器启用 ECS 结构化控制台日志。每个 HTTP 请求记录 method、path、status、durationMs 和 MDC requestId；敏感 Header、JWT、密码、签名和原始回调负载不得写日志。

内部可观测端点：

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`
- 指标 `pms_integration_outbox_events{status=...}`
- 指标 `pms_integration_dead_letters_open`
- 指标 `pms_integration_callbacks_replayed`

建议的初始告警阈值：

| 告警 | 条件 | 等级 |
|---|---|---|
| API 不就绪 | readiness 连续 2 分钟非 UP | P1 |
| 开放死信 | `pms_integration_dead_letters_open > 0` 持续 5 分钟 | P1 |
| Outbox 积压 | PENDING+RETRY 连续 10 分钟增长或超过 100 | P1 |
| 回调签名拒绝 | 5 分钟超过 20 次 | P1/安全 |
| HTTP 5xx | 5 分钟比例超过 2% | P1 |
| P95 延迟 | 10 分钟超过 2 秒 | P2 |

## 7. 备份与恢复演练

运行栈健康后执行：

```powershell
pwsh -File scripts/backup-mysql.ps1
pwsh -File scripts/restore-drill.ps1
```

备份文件和 SHA-256 元数据进入忽略目录 `.artifacts/backups`。恢复脚本只创建经过正则约束的 `pms3_restore_drill_*` 临时库，核对 Flyway v19、88 张表、22 个启用报表、5 个 fail-closed 策略、关键表计数和 Outbox 校验值，最后删除该临时库。`-KeepRestoredDatabase` 只用于受控人工检查。

Flyway 采用 forward-only 策略。迁移失败时先停止写入并保留失败现场；应用镜像回滚只有在 schema 向后兼容时才允许。涉及破坏性 schema 变化时，必须用升级前备份恢复到新的数据库实例并切换流量，不能直接修改 `flyway_schema_history`。

## 8. 空库可重现演练

```powershell
pwsh -File scripts/empty-stack-drill.ps1
```

脚本生成一次性强密码、使用独立 Compose project/端口/卷，从空库构建 API 和 Web，验证 v19/88/22/5/0、API readiness 和登录页，然后仅对校验过的精确 project 执行 `down -v`。它不会读取或输出本地 `.env` 的秘密。

## 9. 供应链门禁

每个候选版本必须留存：

- Maven CycloneDX SBOM 与前端 CycloneDX SBOM；
- Maven/Vitest/Playwright/OpenAPI 漂移结果；
- `npm audit --omit=dev --audit-level=high`；
- Semgrep SAST、Git 跟踪文件秘密字面量扫描；
- API、Web、MySQL 和 Redis 镜像的 Critical/High CVE 扫描。

内部发布门禁为可修复 Critical/High = 0、已跟踪凭据字面量 = 0。任何例外都必须有 CVE、不可达证据、到期时间和责任人；G9 验收不接受用“模拟环境”跳过扫描。

## 10. 生产前外部门禁

以下内容不属于内部代码可伪造的完成项：生产数据迁移授权与脱敏复核、支付/税控/银行/IoT/Java110 正式协议和沙箱资料、真实证书与出口白名单、生产容量指标、灾备 RPO/RTO 签字、业务 UAT 和变更窗口审批。在这些资料齐备前，系统必须继续显示“没有任何生产通道”。
