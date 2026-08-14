# CLAUDE.md — ximu 进销存管理系统

## 项目概况

ximu（进销存管理系统）库存模块：入库/出库/盘点/调拨/安全库存五个子功能。
采用**微服务架构**，拆分为 2 个微服务：

| 微服务 | 端口 | 库 | 职责 |
|--------|------|-----|------|
| **inventory-service** | 8081 | ximu | 入库/出库/盘点/调拨/实时库存/批号管理（操作同一个库存账本，强一致性） |
| **safe-stock-service** | 8082 | ximu | 安全库存配置/阈值管理/补货策略（不碰流水，只读库存做预警） |

## 技术栈（JDK 17）

| 组件 | 版型 |
|------|------|
| JDK | 17（`C:\Users\Administrator\.jdks\ms-17.0.20`） |
| Spring Boot | 3.2.5（兼容 JDK17） |
| MyBatis-Plus | 3.5.9（`mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser`） |
| 数据库 | MySQL 8（容器 mysql8，`localhost:3306`，root/123456，库 `ximu`） |
| 构建工具 | Maven（`D:\IDEA\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3`） |

## 环境变量（每次新 shell 必须设置）

```powershell
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\ms-17.0.20"
$env:MAVEN_HOME = "D:\IDEA\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3"
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"
```

## 构建约定

- **唯一正式构建命令**：在 ximu 根目录 `mvn clean compile -DskipTests`（组长执行，Claude 不要跑 mvn/npm）
- 禁止 Docker 容器（MySQL/Redis 除外）
- 构建产物（target/）不提交 git
- **DDL/种子数据**：`spring.sql.init.mode` 默认为 `never`，schema.sql 不会随启动自动执行；开发环境可临时改为 `always` 自动建表+种子，生产环境必须用 Flyway/Liquibase 或手动管理 DDL

## 禁止事项

- **禁止使用虚拟线程**（JDK 21 特性，本项目 JDK 17）：application.yml 中不要写 `spring.threads.virtual.enabled`
- **禁止跑 mvn/npm build**（组长统一编译验证）
- **禁止 git commit**（组长统一提交）
- **认证不在本模块实现**：本模块不含 Spring Security，统一由网关 / 父应用处理；保持模块可移植，不绑定特定认证方案
- **不引入 Redis 等中间件**：如后续需要缓存 / 限流，由网关层统一接入

## 生产部署须知

- **数据库凭据**：账号密码通过环境变量注入 `DB_USERNAME` / `DB_PASSWORD`，本地开发默认 `root` / `123456`（见各 `application.yml` 的 `${DB_USERNAME:root}` / `${DB_PASSWORD:123456}`）。生产严禁使用默认值，务必设置环境变量。
- **CORS**：允许的前端来源通过 `app.cors.allowed-origins`（逗号分隔）配置，默认仅 `localhost:5173/3000`；生产按实际域名收紧，不要回退为 `*`。
- **认证**：本模块不内置 Spring Security，认证与鉴权由上游网关 / 父应用统一处理后再路由到本服务。
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
