# ADR-007：迁移到受支持的 Spring Boot 3.5 线

- 状态：已接受，实施待 G1 最后一批完成
- 日期：2026-08-24
- 决策范围：`apps/pms-api`

## 背景

当前 API 使用 Java 17 与 Spring Boot 2.7.18。Spring 官方当前把 3.5 和 3.4 列为主动维护版本，并建议迁移到最新受支持版本；3.5 当前文档版本为 3.5.16，最低仍为 Java 17。参考：

- [Spring Boot Supported Versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)
- [Spring Boot 3.5 System Requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring Boot 3.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)

工程当前有 16 个主源码/测试文件使用 `javax.servlet` 或 `javax.validation`，安全配置仍使用 Spring Security 5 的 `authorizeRequests`/`antMatchers`，OpenAPI 使用 springdoc 1.7。直接跨到 Spring Boot 4.1 会同时引入 Spring Framework 7、Servlet 6.1 与 Tomcat 11，超出 G1 身份权限收尾所需的最小变更。

## 决策

1. 在 G1 退出前，把 API 迁移到 Spring Boot 3.5 的最新补丁版本；ADR 编写时目标是 3.5.16，实际合并时重新核对并只选择 3.5.x 补丁版本。
2. 保持 Java 17，不在同一批升级 JDK 或切换 Web 容器。
3. 一次性完成 `javax.*` 到 `jakarta.*`、Spring Security 6 `requestMatchers`/`authorizeHttpRequests`、springdoc 2.x starter、Flyway/MySQL 驱动兼容调整；不维护 Boot 2/3 双分支兼容层。
4. 将框架升级独立成可回滚提交，不与数据库业务迁移或领域重构混合。
5. Spring Boot 4.x 暂不采用；G9 发布门禁重新评估受支持版本和安全公告。

## 验收门槛

- `rg "import javax\." apps/pms-api/src` 为 0；
- Maven 常规测试、真实 MySQL V1—V9 空库迁移和 IAM 集成测试全部通过；
- Docker API/Web 从镜像重新构建并健康；
- OpenAPI 可生成前端类型，Vitest、生产构建和 Playwright 全绿；
- 401、403、429、首次改密、会话撤销和项目隔离语义不回退。

## 回滚

框架升级不新增或修改已执行的 Flyway 迁移。若验收失败，回退独立升级提交并恢复 Boot 2.7 构建；V9 数据结构保持兼容。Boot 2.7 只能作为短期回滚基线，不能作为 G1 通过或发布候选版本。
