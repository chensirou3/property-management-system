# G9 部署、安全、可观测与恢复基线

> 正式交付更新（2026-09-03）：当前默认使用 Flyway V24 和 `PMS_FORMAL_EMPTY_BASELINE=true`，全新部署没有演示业务记录。本文出现的 V23 种子计数属于历史恢复/QA 基线；正式空库验收以 `../delivery/最终交付测试报告-2026-09-03.md` 为准。

更新时间：2026-09-03

## 1. 能力边界

本工程当前只具备内部可验收的模拟集成边界：支付、发票、银行信托和 IoT 为 `SIMULATOR`，Java110 为 `DISABLED`，五个策略的 `production_ready` 均为 `false`。没有支付机构、税控、银行、设备厂商或 Java110 生产授权时，不得把连接测试、模拟投递或回调演练描述为生产接入。

第三方治理台只展示脱敏端点、凭据状态和校验值；不会读取或回显任何密钥。模式切换和密钥变更必须在部署系统完成，不能在浏览器中修改。

## 2. HTTPS 边界

生产入口必须使用可信 CA 证书和 TLS 1.2/1.3。参考配置为宿主机 Nginx 使用的 `deploy/nginx-tls.conf.example`，上线前至少替换域名、证书 Secret 路径，并让其中的 `127.0.0.1:5174` 与 Compose 的固定 Web 发布端口一致。宿主 Nginx 不使用 Docker DNS、也不直连未发布端口的 API；所有 `/api` 请求先进入 Web Nginx，再由 Web 通过 internal `frontend` 网络转发给 API：

- HTTP 全量重定向 HTTPS，并启用 HSTS；
- Compose Web 端口固定绑定宿主 `127.0.0.1:5174`，由同机受控 TLS 反向代理接入；生产验证器要求发布端口恰为 `5174`，即使设置 `PMS_WEB_PORT` 也不得解析为其他值，并继续禁止临时端口 `0`。为与宿主 TLS upstream 保持同一地址族，验证器只接受精确的 IPv4 loopback `127.0.0.1`，拒绝 `::1`、`0.0.0.0` 及其他地址；
- 最外层 TLS 代理覆盖客户端自带的 `X-Forwarded-For` 并清空未受信任的 `Forwarded`；Web 代理只转发该受控值，避免伪造来源 IP 干扰登录锁定和审计；
- 登录按 IP 限制为 5 次/分钟，服务端账号锁定参数在生产固定为 5 次失败、15 分钟窗口和 15 分钟锁定；应用配置绑定同时拒绝零值和越界值；
- 回调入口限制为 30 次/分钟、1 MB 请求体，并继续执行 HMAC、时间窗和幂等校验；
- `/actuator`、`/docs`、`/swagger-ui` 和 `/v3/api-docs` 不向公网开放；探针通过容器网络或编排平台读取；
- 入口生成并透传 `X-Request-Id`，后端日志、审计和回调证据使用同一追踪号。

## 3. Secret 管理

应用已启用 `optional:configtree:/run/secrets/`。`deploy/docker-compose.production.example.yml` 是独立、可由普通 Docker Compose 执行的生产基线，不与开发 `docker-compose.yml` 合并，因此不会先解析开发环境中的明文密码变量。部署系统应让 Secret Manager 在启动前把秘密物化到仓库外的受控目录，并把其绝对路径放入 `PMS_SECRET_DIR`；Compose 只把这些文件挂载到 `/run/secrets`，不要求 Swarm 的 external secret。它仍需结合真实注册表、TLS 入口、备份和变更审批后才能上线。

必须分别生成和托管以下秘密，禁止提交 Git、写入镜像、放入截图或日志：

| Secret | 最低要求 | 轮换影响 |
|---|---|---|
| `JWT_SECRET` | 独立随机值，按 Java `String.length()` 至少 32 个字符，且不得与回调密钥相同 | 现有访问令牌失效 |
| `PMS_CALLBACK_SIGNING_SECRET` | 与 JWT 不同的独立随机值，按 Java `String.length()` 至少 32 个字符 | 需与回调方双边切换 |
| `MYSQL_PASSWORD` | 专用应用账号随机密码，不得与 root 密码相同 | 更新连接池后重启 API |
| `MYSQL_ROOT_PASSWORD` | 仅数据库初始化/受控运维使用，不得与应用账号密码相同 | 按数据库变更流程轮换 |
| `REDIS_PASSWORD` | 专用随机密码或托管 ACL | 更新连接后重启 API |
| `PMS_BOOTSTRAP_ADMIN_PASSWORD` | 12–200 字符，同时含大写、小写、数字、符号，且不含完整登录账号 | 初始化后立即轮换并受控保存 |

`PMS_SECRET_DIR` 中必须恰有部署所需的命名文件：`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET`、`PMS_CALLBACK_SIGNING_SECRET` 和 `PMS_BOOTSTRAP_ADMIN_PASSWORD`。文件必须是非空 UTF-8 文本，`REDIS_PASSWORD` 不得包含 NUL/CR/LF；部署验证器会在启动前执行上表的长度、复杂度和相互独立性检查。只有数据库中尚无该登录账号、确实需要创建 bootstrap 管理员时，API 启动器才会复用同一 `PasswordPolicy` 校验密码，并在弱密码时于查询之后、编码和 INSERT 之前拒绝；已有账号不会因当前 bootstrap Secret 变化而无法启动。普通 Compose 的 file-backed secret 实际是只读 bind mount，宿主文件的 UID 不会自动改成容器内的 `app`、`999` 或 `redis`；因此 Unix 必须让目录严格为 `0700`、六个文件严格为 `0444`。目录的不可遍历性保护宿主侧秘密，文件的只读位则让不同的非 root 容器 UID 在单文件挂载后可读但不可写/执行。Windows 必须把目录 ACL 限制到部署身份和必要管理员，移除 `Everyone`、`Authenticated Users`、内置 `Users/Guests`，并对文件禁用继承、仅授予读取权限且设置 Read-only 属性。目录与文件不得是符号链接或 junction。开发机 `.env` 只允许保留在忽略文件中；生产 Secret 的读取权限仅授予对应工作负载身份。

## 4. 最小权限

生产 Compose 固定使用三个显式网络，不允许服务隐式落入默认网络：

| 网络 | 属性 | 成员 | 用途 |
|---|---|---|---|
| `ingress` | 普通桥接网络 | Web | 仅承载 Web 发布到宿主 loopback 的 TCP 8080 入口 |
| `frontend` | `internal: true` | Web、API | Web 只通过该内部网络访问 API；API 不加入 `ingress` |
| `data` | `internal: true` | API、MySQL、Redis | API 访问数据服务；MySQL、Redis 不加入前端网络且不发布宿主端口 |

因此在 Compose 服务发现范围内，Web 只能通过 `frontend` 解析并访问 API，不能直接解析 MySQL 或 Redis；API 是唯一同时加入 `frontend` 和 `data` 的服务，两个网络均为 internal，所以 API 没有默认公网出口。Linux Smoke 会在 Compose 网络之外启动临时 TCP 探针并断言 API 无法连接。四个容器禁止 `privileged`、`cap_add`、设备、宿主 PID/IPC/UTS/user namespace、额外安全选项、特权生命周期 hook 及任意用户覆盖；`use_api_socket` 必须关闭。API/Web 不得声明数据卷，MySQL/Redis 只能使用清单中两个 project-owned local named volume。除六个已经核准且只读的 file-backed Secret bind 外，禁止任意宿主 bind mount，特别是 `/var/run/docker.sock`；named volume 也禁止 `external` 和 `driver_opts`，避免用 local driver 参数伪装宿主 bind。API 数据库账号只访问指定 MySQL schema。Redis ACL 先清空权限并显式排除 `dangerous`/`admin` 类，再只开放 `pms3:*` key 和连接、Spring 健康探针 `INFO`、字符串、哈希、列表、集合、有序集合、过期、事务等具体应用命令；`FLUSHALL`、`CONFIG` 与全部发布/订阅能力保持禁用。未来增加 Pub/Sub 时必须使用 `pms3:*` channel 并单独评审开放具体命令。未来增加必须出网的生产适配器时，应单独定义受控出口网络及白名单，不能把 API 主服务、MySQL 或 Redis 直接接入普通出口网络。

四项服务必须统一声明 `PMS_TARGET_PLATFORM`。部署脚本应从目标 Linux Docker daemon 读取 server architecture，规范化为 `linux/amd64`、`linux/arm64` 或 `linux/386` 后导出；不得在开发机臆测目标平台，也不得依赖 Compose 静默模拟异构镜像。Linux Smoke 会逐一读取四个运行容器实际使用的 image ID，并核对镜像 OS/architecture 与该值完全一致。

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

备份文件和 SHA-256 元数据进入忽略目录 `.artifacts/backups`。恢复脚本只创建经过正则约束的 `pms3_restore_drill_*` 临时库，核对 Flyway V23、91 张表、22 个启用报表、5 个 fail-closed 策略、4 个已发布看板配置、3 条脱敏访客种子、生产访客连接 0、关键表计数和 Outbox 校验值，最后删除该临时库。`-KeepRestoredDatabase` 只用于受控人工检查。

Flyway 采用 forward-only 策略。迁移失败时先停止写入并保留失败现场；应用镜像回滚只有在 schema 向后兼容时才允许。涉及破坏性 schema 变化时，必须用升级前备份恢复到新的数据库实例并切换流量，不能直接修改 `flyway_schema_history`。

## 8. 空库可重现演练

```powershell
pwsh -File scripts/empty-stack-drill.ps1
```

脚本生成一次性强密码、使用独立 Compose project/端口/卷，从空库构建 API 和 Web，验证 `V23/91/22/5/0/4/3/0`、API readiness 和登录页，然后仅对校验过的精确 project 执行 `down -v`。它不会读取或输出本地 `.env` 的秘密。

## 9. 供应链门禁

每个候选版本必须留存：

- Maven CycloneDX SBOM 与前端 CycloneDX SBOM；
- Maven/Vitest/Playwright/OpenAPI 漂移结果；
- `npm audit --omit=dev --audit-level=high`；
- Semgrep SAST、Git 跟踪文件秘密字面量扫描；
- API、Web、MySQL 和 Redis 镜像的 Critical/High CVE 扫描。

内部发布门禁为 Critical/High = 0、Git 已跟踪及未忽略未跟踪文件中的凭据字面量 = 0。自动化镜像门禁不提供 CVE ignore、allowlist 或 `ignore-unfixed` 绕过；未达到零发现时保持发布失败。

复验命令：

```powershell
$env:PMS_SECURITY_REFRESH = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
docker compose build --pull --no-cache --build-arg "PMS_SECURITY_REFRESH=$env:PMS_SECURITY_REFRESH" api web
pwsh -File scripts/generate-sbom.ps1
pwsh -File scripts/scan-tracked-secrets.ps1
pwsh -File scripts/scan-container-images.ps1
```

API 与 Web 运行层在构建时显式要求 OpenSSL `3.5.8-r0` 或更高版本；版本地板不满足时构建直接失败。候选构建必须同时使用 `--pull` 和 `--no-cache`：前者刷新基础镜像，后者防止在 APK 仓库已更新而基础摘要未变化时复用旧的 `apk upgrade` 层。`PMS_SECURITY_REFRESH` 会进入安全更新层，便于审计并支持受控缓存失效，但不能代替无缓存的安全候选构建。

镜像扫描脚本先下载 OS 与 Java 漏洞库并拒绝过期/异常元数据，扫描时同时使用 `--skip-db-update` 和 `--skip-java-db-update`，保证四个镜像共享同一对数据库快照；随后冻结每个候选的本地 image ID、按该 ID 扫描，并在结束时确认原引用没有漂移。`.artifacts/security/trivy/summary.json` 以 schema v3 保存固定 Trivy 镜像摘要/版本、两套数据库的版本与时间、候选引用、本地 image ID、注册表摘要、平台、扫描目标、包数量、原始报告文件名及其 SHA-256 和计数；验证器会重新读取原始报告并重算这些字段，且对缺字段、空扫描目标、空包清单、API 缺少 Java/JAR 目标、哈希或平台不符、过期数据库以及非零 Critical/High 一律拒绝。

生产文件不在服务器现场构建镜像。`PMS_API_IMAGE`、`PMS_WEB_IMAGE`、`PMS_MYSQL_IMAGE` 和 `PMS_REDIS_IMAGE` 必须采用注册表不可变形式 `registry/repository@sha256:<64-hex>`；只给 `latest` 或版本标签会被 `scripts/validate-production-deployment.ps1` 拒绝。部署前必须在目标 Linux Docker 主机导出与 daemon 架构一致的 `PMS_TARGET_PLATFORM`，四项服务均据此拉取同平台镜像。API 的五项秘密只通过 configtree 文件挂载；MySQL 使用官方 `_FILE` 入口变量；Redis 拒绝含 CR/LF 的密码，只在 tmpfs 中生成含 SHA-256 密码摘要和 `pms3:*` key/channel 范围的 ACL 与配置，原始密码不写入 Compose 环境、持久卷或最终进程参数；ACL 先排除管理/危险类别，再按具体命令白名单仅回加应用操作与健康探针所需 `INFO`，Pub/Sub 保持禁用。

当前仓库 CI 没有注册表 push 权限和发布步骤，因此 CI 的 `pms3-replica-*:ci` 报告只证明本地候选 image ID，不等同于生产 manifest digest。正式发布流水线必须先推送候选，再按返回的 registry manifest digest 拉取和复扫，最后执行：

```powershell
$env:PMS_SECRET_DIR = '<仓库外受控 Secret 目录的绝对路径>'
pwsh -File scripts/validate-production-deployment.ps1 -EvidencePath .artifacts/security/trivy/summary.json
# 部署完成后只读核验四个运行容器
pwsh -File scripts/validate-production-deployment.ps1 -EvidencePath .artifacts/security/trivy/summary.json -VerifyRunningContainers
```

第一个命令先验证独立 Compose 没有 `build`、四个镜像均为摘要、API 不含明文秘密或未批准环境变量、六个 Secret 文件的路径/内容/权限与语义约束，并强制网络集合恰为 `ingress`/`frontend`/`data`、网络名称归当前 Compose project 所有、不得 external/shared、只准本地 bridge 且后两者 `internal=true`；服务成员关系必须符合上表，API/MySQL/Redis 均不得发布宿主端口，Web 必须恰有一条绑定 `127.0.0.1`、宿主端口恰为 `5174` 的 TCP 8080 映射。API 环境采用精确键白名单，回调时间窗、监听端口、文档开关和登录保护值固定；MySQL 环境同样采用精确键集合，API 的数据库 URL/用户必须与内部 MySQL 服务身份一致，因此 `SPRING_APPLICATION_JSON`、`JAVA_TOOL_OPTIONS`、`PMS_SECURITY_*` 等高优先级覆盖载体会被拒绝。静态门禁还逐服务拒绝危险权限、未核准卷、宿主 bind、Docker socket、外部卷及带 `driver_opts` 的伪装 bind，并锁定 MySQL command、Redis ACL bootstrap entrypoint 及四个健康检查的命令和时序；API/Web/Redis 不得额外覆盖 command，API/Web/MySQL 不得额外覆盖 entrypoint。随后要求四个 manifest digest 各自具有唯一且零 Critical/High 的扫描证据。第二个命令还同时核对容器的 `.Config.Image` 等于 Compose manifest digest、`.Image` 等于证据中的本地 image ID，运行网络的 driver/scope/internal/attachable/project 标签、容器实际网络附着、最终 Entrypoint/Cmd、Healthcheck，以及“镜像 Env + Compose Env”与容器实际 Env 均没有漂移，并核对 `.HostConfig.Privileged`/`CapAdd`/设备/namespace、安全选项与完整 `.Mounts` 清单；只有两个审批 named volume 和六个只读 Secret bind 可出现，四项服务必须健康且 PID 1 的真实 UID 不得为 0。`-ConfigOnly -SkipSecretFileContentChecks` 仅供 CI 检验 Compose 结构，真实部署前不能跳过文件及语义检查。Linux CI 还使用独立临时 project、Secret 目录和命名卷实际启动 MySQL、Redis、API、Web，要求四项均为 `healthy`，断言目标 Docker server 平台与四个实际运行镜像平台一致、三个运行网络及其 internal 属性、四个容器的实际网络成员关系、Web 到 API 的健康访问，以及 API 无法连接 Compose 网络外的临时探针；同时直接读取每个容器 `/proc/1/status` 证明 PID 1 非 root，以容器真实非 root UID 读取各自挂载的 Secret，并实测 Redis `PING`、`pms3:*` 写读删成功，越界 key、`FLUSHALL`、`CONFIG` 和 Pub/Sub 被 ACL 拒绝。测试结束只删除该精确临时 project 的容器/网络/卷和探针。基础构建镜像也在 CI 中先解析为摘要。摘要固定会冻结补丁状态，因此 CI 仍需定期重新解析上游摘要、无缓存构建并使用最新漏洞库复扫。

G9 候选实现固定 Netty 4.1.136.Final 和 Flyway 11.20.3，使用 MySQL 8.4 加固镜像、`nginxinc/nginx-unprivileged:1.29-alpine` Web 运行层，并在 API/Web Alpine 运行层执行安全更新。MySQL 镜像移除了本工程不使用的 MySQL Shell Python 运行时和仅 root 入口需要的 gosu，最终以 `999:999` 运行；API 为 `app`，Web 为 `101:101`。

2026-08-25 G10 最终证据：API/Web CycloneDX 分别包含 96/300 个组件，SHA-256 分别为 `7dc9298225ad18f008aa8e35e88d9f69f1aa91cbe6ba0fcb80a6cb49edc404be` / `d419dd04b3baa9225e98ae2d8351af9fe3bdf07ae6df3c50947c90a1a2cb9692`；npm 生产依赖 0 漏洞；Semgrep 扫描 186 个目标、实际执行 365 条规则、0 发现；Secret 扫描覆盖 410 个仓库文件并与 5 个本地秘密值比对、0 发现；固定摘要的 Trivy 扫描 API/Web/MySQL/Redis 四个最终镜像，Critical=0、High=0。完整数值、边界与摘要见 `acceptance-report.md`。

## 10. 生产前外部门禁

以下内容不属于内部代码可伪造的完成项：生产数据迁移授权与脱敏复核、支付/税控/银行/IoT/Java110 正式协议和沙箱资料、真实证书与出口白名单、生产容量指标、灾备 RPO/RTO 签字、业务 UAT 和变更窗口审批。在这些资料齐备前，系统必须继续显示“没有任何生产通道”。
