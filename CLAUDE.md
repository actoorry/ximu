# CLAUDE.md — jxc 进销存管理系统

## 项目概况

jxc（进销存管理系统）库存模块：入库/出库/盘点/调拨/安全库存五个子功能。
采用**微服务架构**，拆分为 2 个微服务：

| 微服务 | 端口 | 库 | 职责 |
|--------|------|-----|------|
| **inventory-service** | 8081 | jxc | 入库/出库/盘点/调拨/实时库存/批号管理（操作同一个库存账本，强一致性） |
| **safe-stock-service** | 8082 | jxc | 安全库存配置/阈值管理/补货策略（不碰流水，只读库存做预警） |

## 技术栈（JDK 17，不是 21）

| 组件 | 版型 |
|------|------|
| JDK | 17（`C:\Users\Administrator\.jdks\ms-17.0.20`） |
| Spring Boot | 3.2.5（兼容 JDK17） |
| MyBatis-Plus | 3.5.9（`mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser`） |
| 数据库 | MySQL 8（容器 mysql8，`localhost:3306`，root/123456，库 `jxc`） |
| 构建工具 | Maven（`D:\IDEA\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3`） |

## 环境变量（每次新 shell 必须设置）

```powershell
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\ms-17.0.20"
$env:MAVEN_HOME = "D:\IDEA\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3"
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"
```

## 构建约定

- **唯一正式构建命令**：在 jxc 根目录 `mvn clean compile -DskipTests`（组长执行，Claude 不要跑 mvn/npm）
- 禁止 Docker 容器（MySQL/Redis 除外）
- 构建产物（target/）不提交 git

## 禁止事项

- **禁止使用虚拟线程**（JDK 21 特性，本项目 JDK 17）：application.yml 中不要写 `spring.threads.virtual.enabled`
- **禁止引入 Spring Security**（本阶段无需认证，纯 CRUD + 状态机）
- **禁止引入 Redis**
- **禁止跑 mvn/npm build**（组长统一编译验证）
- **禁止 git commit**（组长统一提交）

## 前后端分离 API 契约

- 统一返回结构：`{ "code": 0, "message": "ok", "data": {} }`，code=0 成功
- 分页返回：`{ "code": 0, "message": "ok", "data": { "list": [...], "total": N } }`
- 前端传参：`page`（从 1 开始）、`size`（默认 10）、`keyword`（模糊搜索）
- API 路径：`/api/<module>/<resource>`
- Jackson 用默认 camelCase 序列化（不要全局 SNAKE_CASE）
- 日期格式：`yyyy-MM-dd HH:mm:ss`（LocalDateTime 字段加 `@JsonFormat`）
