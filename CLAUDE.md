# CLAUDE.md — ximu 进销存管理系统

## 项目概况

ximu（进销存管理系统）库存模块：入库/出库/盘点/调拨/安全库存五个子功能。
采用**微服务架构**，5 个 Maven 模块（2 个微服务 + 2 个共享库 + 统一网关）：

| 模块 | 端口 | 库 | 职责 |
|------|------|-----|------|
| **ximu-common** | —（共享库） | — | RBAC 基座：Role/Auths/OperatorContext/Operator/Result/PageQuery/DimsNormalizer，随服务打包 |
| **ximu-common-web** | —（共享库） | — | 跨服务共享 Web 基础设施：审计日志三件套/全局异常/CORS/审计填充/MyBatis-Plus 配置/身份过滤器/RBAC `@RequireRole` 注解与切面，随业务服务打包；gateway 不依赖（reactive 纯净） |
| **ximu-gateway** | 8080 | — | 认证边缘：JWT 校验 + 剥离伪造身份头 + 注入 X-User-* + 路由转发 + CORS |
| **inventory-service** | 8081 | ximu | 入库/出库/盘点/调拨/实时库存/批号管理（操作同一个库存账本，强一致性），独占 Flyway 迁移权 |
| **safe-stock-service** | 8082 | ximu | 安全库存参数配置维护（有货率/Z值/补货周期/订货点/最高库存/安全库存，仅 CRUD，不做预警计算） |

## 技术栈（JDK 17）

| 组件 | 版型 |
|------|------|
| JDK | 17（`C:\Users\Administrator\.jdks\ms-17.0.20`） |
| Spring Boot | 3.5.16 + Spring Cloud 2025.0.1（2026-08 P1-6 升级，兼容 JDK17；4.x 迁移另立项） |
| MyBatis-Plus | 3.5.12（`mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser`；IService/ServiceImpl 自 3.5.9 起在 `mybatis-plus-spring` 模块，直接依赖 `mybatis-plus-extension` 的模块须显式引入，见 common-web pom） |
| springdoc | 2.8.17（OpenAPI 文档，与 Boot 3.5.x 匹配） |
| 数据库 | MySQL 8（容器 mysql8，`localhost:3306`，root/123456，库 `ximu`） |
| 构建工具 | Maven（`D:\IDEA\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3`） |

## 环境变量（每次新 shell 必须设置）

```powershell
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\ms-17.0.20"
$env:MAVEN_HOME = "D:\IDEA\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3"
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"
```

## 构建约定

- **唯一正式构建命令**：在 ximu 根目录 `mvn clean compile -DskipTests`
- 禁止 Docker 容器（MySQL/Redis 除外）
- 构建产物（target/）不提交 git
- **DDL/种子数据**：开发一律走 Flyway（`FLYWAY_ENABLED=true`，inventory-service 独占迁移权，V1~V10）；`schema.sql` 已弃用（P2-31），`spring.sql.init.mode` 恒为 `never` 不再作建表路径；演示种子在迁移完成后手工执行 `ximu-inventory-service/src/main/resources/db/demo/demo_seed.sql`

## 禁止事项

- **禁止使用虚拟线程**（JDK 21 特性，本项目 JDK 17）：application.yml 中不要写 `spring.threads.virtual.enabled`
- **禁止跑 mvn/npm build**（组长统一编译验证）
- **禁止 git commit**（组长统一提交）
- **认证不在本模块实现**：业务服务不含 Spring Security，统一由 ximu-gateway（JWT 校验 + 身份头注入）处理；保持模块可移植，不绑定特定认证方案
- **不引入 Redis 等中间件**：如后续需要缓存 / 限流，由网关层统一接入

## 生产部署须知

- **数据库凭据**：账号密码通过环境变量注入 `DB_USERNAME` / `DB_PASSWORD`，本地开发默认 `root` / `123456`（见各 `application.yml` 的 `${DB_USERNAME:root}` / `${DB_PASSWORD:123456}`）。生产严禁使用默认值，务必设置环境变量；且按服务拆分账号最小授权（`ximu_inventory` 全表+迁移权 / `ximu_safestock` 仅两张表 DML，模板见 `db/grants/service_accounts.sql`）。
- **网关凭据**：`JWT_SECRET`（网关 HS256 密钥，>=32 字节随机串）与 `GATEWAY_TOKEN`（网关与两业务服务共享的内部令牌）生产必须设置；两业务服务在 `auth.enabled=true` 时缺失 `GATEWAY_TOKEN` 直接拒绝启动（R2-P1-4 fail-closed，无「空串关闭校验」逃生门）；网关弱密钥/缺令牌同样拒绝启动（GatewaySecretChecker fail-fast）。密钥用 `openssl rand -base64 48` 生成。
- **CORS**：允许的前端来源通过 `app.cors.allowed-origins`（逗号分隔）配置，默认仅 `localhost:5173/3000`；生产按实际域名收紧，不要回退为 `*`。R2-P2-7 已知风险接受：两业务服务自身也开放 CORS 与「仅网关对外」的定位并存（网关 CORS 仍为唯一入口面）；若部署确认只经网关对外，将下游 `app.cors.allowed-origins` 留空（不配置）即关闭服务自身 CORS，收敛攻击面。
- **认证**：本模块不内置 Spring Security，认证与鉴权由 ximu-gateway 统一处理后再路由到本服务；生产保持各服务 `app.auth.enabled: true`，仅本地开发可临时关闭。
- **DDL 管理**：`spring.sql.init.mode` 生产保持 `never`，schema 变更走 Flyway/Liquibase 或人工 DBA 流程。
- **健康检查**：已引入 Spring Boot Actuator，仅暴露 `/actuator/health` 与 `/actuator/info`（见 `management.endpoints.web.exposure.include`）。
- **连接池**：HikariCP 参数已在 `application.yml` 配置（`maximum-pool-size` 等），按生产负载调整。
- **乐观锁**：所有业务表含 `version` 列，状态机流转（approve/check/complete）依赖 MyBatis-Plus `@Version` + `OptimisticLockerInnerInterceptor` 防止并发覆盖。

## 前后端分离 API 契约

- 统一返回结构：`{ "code": 0, "message": "ok", "data": {} }`，code=0 成功
- 分页返回：`{ "code": 0, "message": "ok", "data": { "list": [...], "total": N } }`
- 前端传参：`page`（从 1 开始）、`size`（默认 10）、`keyword`（模糊搜索）
- API 路径：`/api/<module>/<resource>`
- Jackson 用默认 camelCase 序列化（不要全局 SNAKE_CASE）
- 日期格式：`yyyy-MM-dd HH:mm:ss`（LocalDateTime 字段加 `@JsonFormat`）
