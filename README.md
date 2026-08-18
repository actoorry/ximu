# ximu 进销存管理系统

> 基于 Spring Boot 微服务架构的进销存库存管理模块，覆盖入库、出库、盘点、调拨、安全库存五大核心业务。

## 架构定位

ximu 定位为**模块化、可插拔的开源进销存系统**：四个 Maven 模块（公共基座 / 网关 / 库存账本服务 / 安全库存服务）独立部署、独立启停伸缩，不嵌入任何父应用，也不绑定特定认证方案或前端。认证在网关边缘统一处理，业务服务只信任网关注入的 `X-User-*` 身份头；库存账本由 inventory-service 独占 Flyway 迁移权，安全库存服务可选装、可整体下线。

> 详细模块地图、可插拔点清单、部署形态与扩展指南见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 项目介绍

ximu 是一个进销存管理系统，聚焦库存域的业务闭环。系统围绕「单据」展开——每张入库单、出库单、盘点单、调拨单都遵循「制单 → 批准 → 审核」的流转流程，单据流转到终态时自动联动实时库存，保证账实一致。

系统采用微服务架构，按业务耦合度拆分为两个独立服务：库存操作服务负责操作同一库存账本的入库、出库、盘点、调拨，安全库存服务负责安全库存参数的配置维护。两者独立部署、独立扩容。

## 功能特性

### 库存操作服务（端口 8081）

| 模块 | 功能 |
|------|------|
| 入库管理 | 入库单制单、批准、保管员审核三级流转，支持多商品明细，审核后自动增加库存 |
| 出库管理 | 出库单制单、批准流转，批准后自动扣减库存并校验库存充足 |
| 盘点管理 | 盘点单制单、批准、审核流转，审核后按实盘数量校正库存 |
| 调拨管理 | 库位间调拨，制单、批准、完成流转 |
| 库存统计 | 实时库存查询，库龄预警（超过阈值自动标红） |
| 批号管理 | 批次号维护 |
| 操作日志 | 自动记录所有增删改与状态流转 |

### 安全库存服务（端口 8082）

| 模块 | 功能 |
|------|------|
| 安全库存 | 有货率、Z 值、补货周期、经济补货量、订货点、最大库存、安全库存等参数配置 |

## 技术栈

| 端 | 技术 |
|----|------|
| 语言 / 运行时 | Java 17 |
| 框架 | Spring Boot 3.5.16 + Spring Cloud 2025.0.1 |
| 数据访问 | MyBatis-Plus 3.5.12 |
| API 文档 | springdoc-openapi 2.8.17 |
| 数据库 | MySQL 8 |
| 构建工具 | Maven |
| 辅助 | Lombok、Spring Validation、Spring AOP、Spring Boot Actuator |

## 快速开始

环境要求：JDK 17、Maven 3.9+、MySQL 8。

1. 初始化数据库：**Flyway 是唯一初始化路径**——空库（`CREATE DATABASE ximu`）直接启动 inventory-service 即自动执行 V1~V6+ 全量迁移；存量旧库首次启动自动打 baseline 跳过 V1、从 V2 起增量迁移（跑 V4 前先人工执行 `db/precheck/pre_v4_negative_stock_report.sql` 核对负值/NULL 归零清单）。需要演示数据时，迁移完成后手工执行 `ximu-inventory-service/src/main/resources/db/demo/demo_seed.sql`。
2. 配置数据库连接：通过环境变量 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 注入连接串与账号密码（本地开发默认连接 `localhost:3306/ximu`、账号 `root` / `123456`），或直接修改各服务的 `application.yml`。
3. 构建：

```bash
mvn clean package -DskipTests
```

4. 启动服务（本地开发显式激活 dev profile，提供开发兜底密钥；缺 profile 且无环境变量时启动失败）：

```bash
java -jar ximu-gateway/target/ximu-gateway-1.0-SNAPSHOT.jar --spring.profiles.active=dev
java -jar ximu-inventory-service/target/ximu-inventory-service-1.0-SNAPSHOT.jar --spring.profiles.active=dev
java -jar ximu-safe-stock-service/target/ximu-safe-stock-service-1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

5. 访问接口（经网关 `http://localhost:8080`；直连 8081/8082 需带 `-H "X-Gateway-Token: dev-shared-gateway-token"` 与 `X-User-Id` 身份头）：

- 库存操作服务：`http://localhost:8081/api/inventory/{inbound|outbound|check|transfer|stock|batch|log}`
- 安全库存服务：`http://localhost:8082/api/safe-stock`
- 健康检查：`http://localhost:8081/actuator/health`

统一返回结构：`{ "code": 0, "message": "ok", "data": {} }`，`code=0` 表示成功；分页返回 `{ "list": [...], "total": N }`。

## 生产部署

> 生产环境严禁使用任何默认凭据，所有敏感配置一律通过环境变量注入。业务服务通过 `--spring.profiles.active=prod` 激活生产样板（`application-prod.yml`），缺失必填环境变量会启动失败。

### 环境变量清单

| 环境变量 | 含义 | 示例 | 必须性 |
|----------|------|------|--------|
| DB_URL | MySQL 连接串（含库名、字符集、时区） | jdbc:mysql://db.internal:3306/ximu?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8 | 生产必须（prod 无默认值） |
| DB_USERNAME | 数据库账号 | ximu_inventory | 生产必须（prod 无默认值） |
| DB_PASSWORD | 数据库密码 | （强随机密码） | 生产必须（prod 无默认值） |
| JWT_SECRET | JWT 签名密钥（仅网关使用） | （>=32 字节随机串） | 生产必须（网关默认值仅本地开发兜底） |
| JWT_ISSUER | JWT 签发方声明（可选，配置即强制校验 iss） | ximu-auth | 可选（生产建议配置，防跨系统 token 混用） |
| JWT_AUDIENCE | JWT 受众声明（可选，配置即强制校验 aud） | ximu-services | 可选（生产建议配置） |
| GATEWAY_TOKEN | 网关与下游服务的内部共享令牌（防直连伪造身份） | （随机串，网关与两业务服务一致） | 生产必须（prod 无默认值） |
| CORS_ORIGINS | 允许的前端来源，逗号分隔多个域名 | https://erp.example.com,https://admin.example.com | 生产必须（prod 无默认值） |

> 说明：`JWT_SECRET` 必须是 >=32 字节随机串（HS256 密钥），可用 `openssl rand -base64 48` 生成。`GATEWAY_TOKEN` 是网关与两业务服务共享的内部令牌，网关校验 JWT 后注入 `X-Gateway-Token` 头，下游据此确认请求确经网关。生产激活 `application-prod.yml` 后，`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `CORS_ORIGINS` / `JWT_SECRET` / `GATEWAY_TOKEN` 均无默认值，缺失即启动失败。
>
> **数据库账号最小授权（P2-8）**：生产禁止两服务共用同一账号——按服务拆分 `ximu_inventory`（全表 DML + Flyway 迁移 DDL 权限）与 `ximu_safestock`（仅 `inventory_safe_stock` DML + `operation_log` 写入，无任何 DDL），建账号 DDL 模板见 `ximu-inventory-service/src/main/resources/db/grants/service_accounts.sql`，各服务通过自己的 `DB_USERNAME` / `DB_PASSWORD` 注入对应账号。

### 启动顺序

1. 先启动两个业务服务（二者相互独立，无依赖）：
   - ximu-inventory-service（端口 8081）
   - ximu-safe-stock-service（端口 8082）
2. 再启动网关 ximu-gateway（端口 8080）。

```bash
# 1) 业务服务（激活生产 profile）
java -jar ximu-inventory-service/target/ximu-inventory-service-1.0-SNAPSHOT.jar --spring.profiles.active=prod
java -jar ximu-safe-stock-service/target/ximu-safe-stock-service-1.0-SNAPSHOT.jar --spring.profiles.active=prod

# 2) 网关
java -jar ximu-gateway/target/ximu-gateway-1.0-SNAPSHOT.jar
```

> 网关通过 uri 直连 8081/8082 两个服务，因此业务服务必须先于网关就绪；跨机部署时需把网关 `application.yml` 中的 uri 改为内网地址。

### 网关入口

- 统一入口：http://localhost:8080
- 库存操作：http://localhost:8080/api/inventory/** → 8081
- 安全库存：http://localhost:8080/api/safe-stock/** → 8082

### 健康检查

| 组件 | 端点 |
|------|------|
| 网关 | http://localhost:8080/actuator/health |
| 库存操作服务 | http://localhost:8081/actuator/health |
| 安全库存服务 | http://localhost:8082/actuator/health |

（仅暴露 health、info 端点，见各服务 `management.endpoints.web.exposure.include`。）

### ⚠️ 安全警告：网络隔离

两个业务服务只信任网关注入的身份头 X-User-Id / X-User-Name / X-User-Roles，自身不内置认证，因此生产必须：

- 将 8081 / 8082 两个业务服务网络隔离在网关之后（防火墙 / 安全组只放行 8080，不对外暴露 8081 / 8082）。
- 设置 `GATEWAY_TOKEN`（网关注入 X-Gateway-Token 头、下游校验），作为网络隔离之外的代码层兜底：直连伪造 X-User-* 但无正确网关令牌的请求会被 401 拒绝。
- 严禁客户端绕过网关直连业务服务，否则攻击者可伪造 X-User-* 头冒充任意用户，认证形同虚设。
- 保持各服务 `app.auth.enabled: true`（默认即如此），仅本地开发可临时关闭。

### 幂等提交（防重复建单）

四张单据（入库/出库/盘点/调拨）的创建接口支持可选幂等键 `requestId`：

- 生产前端**必须**为每次创建请求生成唯一 `requestId`（如 UUID），网络重试/双击时复用同一值，服务端命中唯一索引即返回已建单据、不重复建单。
- `requestId` 不传时兼容旧客户端，但此时服务端无法区分「重试」与「合法重复建单」，双击/重试仍会重复建单。

## 目录结构

```
ximu
├── ximu-common                公共模块（统一返回结构、分页基类）
├── ximu-inventory-service     库存操作微服务（端口 8081）
├── ximu-safe-stock-service    安全库存微服务（端口 8082）
├── ximu-gateway               统一网关（端口 8080，JWT 校验 + 身份头注入）
└── pom.xml                    父工程（依赖版本管理）
```

## 版本

1.0-SNAPSHOT