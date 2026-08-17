# ximu 架构定位文档

> 状态：已确认。本文档落地 `PRODUCTION-READINESS-PLAN.md` 决策 3 的最终结论，作为 ximu 架构定位的唯一权威依据；文中每条断言均对应源码或配置，并以相对路径标注依据。

## 一、定位声明

ximu 是一个**模块化、可插拔的开源进销存系统**，聚焦库存域的业务闭环（入库 / 出库 / 盘点 / 调拨 / 实时库存 / 安全库存）。

- **模块化**：根 `pom.xml` 声明四个 Maven 模块（`ximu-common` / `ximu-gateway` / `ximu-inventory-service` / `ximu-safe-stock-service`），边界由端口、库表与依赖方向界定。
- **可插拔**：认证、DDL 管理、CORS、凭据、预警判定策略均为可替换 / 可开关的插拔点（见第三节），实现细节不写进业务代码。
- **独立部署**：多模块独立启停、独立扩容，**不嵌入任何父应用**；每个服务自带完整 Spring Boot 启动入口。
- **不绑定认证方案**：业务服务不引入 Spring Security，只信任网关注入的身份头契约；网关可替换为任意 JWT / SSO 实现。
- **不绑定前端**：前后端分离，统一返回结构 `{ code, message, data }` + CORS 白名单，任意前端均可接入。

## 二、模块地图

| 模块 | 端口 | 职责 | 可插拔 / 独立性 |
|------|------|------|----------------|
| `ximu-common` | —（共享库） | 可复用 RBAC 基座：`Role`（5 内置角色）、`Auths`（角色校验 + 职责分离）、`OperatorContext`（ThreadLocal 操作人上下文）、`Operator`、`ForbiddenException`（→403）、`Result`、`PageQuery` | 被各服务依赖、随服务打包，不独立部署 |
| `ximu-gateway` | 8080 | 认证边缘：校验 JWT → 剥离伪造头 → 注入 `X-User-*` 身份头 → 路由转发 → 全局 CORS | 可替换（任意 JWT/SSO 实现）、可旁路（dev 直连服务） |
| `ximu-inventory-service` | 8081 | 库存账本核心：入库/出库/盘点/调拨/实时库存/批号/操作日志，写同一库存账本 | 独占 `ximu` 库的 Flyway 迁移权 |
| `ximu-safe-stock-service` | 8082 | 预警策略：安全库存/阈值/补货参数（有货率、Z 值、补货周期、经济补货量、订货点、最大库存、安全库存）的配置维护 | 可选装，停用不影响账本 |

> 四个模块共享同一个 MySQL 库 `ximu`。账本（`inventory_stock` 及四张单据明细）只由 inventory-service 写入；safe-stock 仅维护自身 `inventory_safe_stock` 配置表，不写库存账本。库龄预警判定（`InventoryStock.isWarn`）在 inventory-service 内实现。

## 三、可插拔点清单

| 插拔点 | 开关 / 变量 | 默认值 | 依据 |
|--------|-------------|--------|------|
| 认证可插拔 | `app.auth.enabled` | `true` | 两服务 `OperatorContextFilter` 读 `X-User-Id/X-User-Name/X-User-Roles` 头，缺头 401；服务 pom 无 Spring Security |
| DDL 管理可插拔 | `FLYWAY_ENABLED`、`spring.sql.init.mode` | `true` / `never` | inventory `application.yml`、safe-stock `application.yml` |
| CORS 可配 | `CORS_ORIGINS` | `http://localhost:5173,http://localhost:3000` | 网关 `application.yml` + 两服务 `CorsConfig` |
| 凭据全部外置 | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `CORS_ORIGINS` | prod 无默认值 | 两个 `application-prod.yml` |
| 预警判定策略 | 代码静态纯函数（无开关） | 动态优先 / 静态回退 | `InventoryStock.isWarn(...)` / `InventoryStock.stockAgeDays(...)` |

各插拔点细节如下。

### 3.1 认证可插拔（头契约）

业务服务**不引 Spring Security**（`ximu-inventory-service/pom.xml`、`ximu-safe-stock-service/pom.xml` 均无 spring-security 依赖），只信任网关注入的头契约：

```text
X-User-Id: 1
X-User-Name: 张三
X-User-Roles: ADMIN,CREATOR
```

- 网关 `JwtAuthGlobalFilter`（`ximu-gateway/src/main/java/com/by/ximu/gateway/JwtAuthGlobalFilter.java`）：校验 `Authorization: Bearer <token>` → 显式 `h.remove("X-User-Id" / "X-User-Name" / "X-User-Roles")` 剥离客户端伪造值 → 注入可信身份头 → 放行；白名单 `app.auth.skip-prefixes=/actuator` 跳过。
- 服务侧 `OperatorContextFilter`：`app.auth.enabled=true`（默认/生产）时，`/api/**` 缺 `X-User-Id` 直接 401；设为 `false`（仅本地开发）以 `dev`/`ADMIN` 身份兜底。
- **替换网关** = 换成任意 JWT / SSO / OIDC 实现，只要在转发前注入同一组 `X-User-*` 头，业务服务零改动。

### 3.2 DDL 管理可插拔

```yaml
# ximu-inventory-service/src/main/resources/application.yml
spring:
  flyway:
    enabled: ${FLYWAY_ENABLED:true}     # 生产固定 true；dev 想用 sql.init 建表可设 false
    baseline-on-migrate: true           # 存量库先打 baseline 1 跳过 V1
    baseline-version: 1
    locations: classpath:db/migration
  sql:
    init:
      mode: never                        # dev 可临时改为 always 自动建表+种子
```

- inventory-service 独占迁移权（pom 引 `flyway-core` + `flyway-mysql`）；safe-stock 显式 `flyway.enabled: false`，防同库双写。
- 两条建表路径互斥：`FLYWAY_ENABLED=true` 走 Flyway 迁移；`sql.init.mode=always` 走 `schema.sql`，二者别同时开。

### 3.3 CORS 可配

```yaml
app:
  cors:
    allowed-origins: "${CORS_ORIGINS:http://localhost:5173,http://localhost:3000}"  # 逗号分隔
```

- 网关 `application.yml` 的 `globalcors.allowedOriginPatterns` 与两服务 `CorsConfig`（读 `app.cors.allowed-origins`）同源同变量；`allowCredentials=true` 时不回退 `*`。

### 3.4 凭据全部环境变量外置

```yaml
# application-prod.yml（两服务一致）
spring:
  datasource:
    url: "${DB_URL}"            # 无默认值
    username: "${DB_USERNAME}"  # 无默认值
    password: "${DB_PASSWORD}"  # 无默认值
app:
  cors:
    allowed-origins: "${CORS_ORIGINS}"  # 无默认值
```

- prod 样板缺失任一必填变量即抛 `Could not resolve placeholder` **启动失败**（fail-fast）；默认值仅存在于 `application.yml`（本地兜底，严禁出生产）。

### 3.5 预警判定策略（动态优先 / 静态回退）

```java
// InventoryStock.java（inventory-service）
public static Long stockAgeDays(LocalDateTime firstInboundAt, LocalDateTime now) { ... }   // 读时由 first_inbound_at 计算
public static boolean isWarn(Long stockAgeDays, Integer stockAge, Integer ageWarnDays) { ... }
```

- 规则：`ageWarnDays` 为 null → `false`；`stockAgeDays`（动态，由 `first_inbound_at` 算）非 null → 按 `stockAgeDays >= ageWarnDays`；否则回退静态列 `stockAge >= ageWarnDays`。
- `stock_age`（遗留静态列，恒 0）与 `stockAgeDays`（读时计算）语义并存，判定以动态值优先。

## 四、部署形态

- **4 个 artifact，3 个可独立部署**：根 `pom.xml` 4 个模块产出 4 个构建产物——3 个可执行 Spring Boot jar（inventory-service / safe-stock-service / gateway）+ 1 个共享库 `ximu-common`（打进各服务，不单独部署）。
- **独立启停伸缩**：三服务各占独立端口（8081 / 8082 / 8080）、独立进程、独立 `application.yml`，可分别启停与水平扩容；inventory 与 safe-stock 之间无运行时调用依赖（共享 DB 而非互相调用）。
- **safe-stock 可整体下线**：它是账本的“旁路”，停用后入库 / 出库 / 盘点 / 调拨 / 实时库存照常运行，仅失去安全库存配置与补货参数能力。
- **启动顺序**：先起两个业务服务，再起网关（网关 uri 直连 8081/8082）；生产须把 8081/8082 网络隔离在网关之后，禁止客户端绕过网关直连（否则可伪造 `X-User-*` 头）。

## 五、扩展指南：如何新增一个业务模块

以 inventory-service 的 `module/inbound` 为模板，新增 `module/<name>` 包，按以下约定落地：

1. **包结构**：`module/<name>/` 下放 `Xxx`（实体）、`XxxMapper`、`XxxService`、`XxxController`，及 `XxxCreateRequest` / `XxxUpdateRequest`（白名单 DTO）/ `XxxDetailVO`。
2. **鉴权**：Controller 保持薄层，鉴权下沉到 Service 事务内：

   ```java
   Auths.requireRole(Role.CREATOR, Role.ADMIN);          // 角色校验（ADMIN 内置旁路）
   Auths.requireNotSelfOrAdmin(head.getCreatedBy());     // 职责分离
   Auths.requireCreatorOrAdmin(head.getCreatedBy());     // 仅本人 / 管理员
   ```

   操作人从 `OperatorContext.getOperatorId() / getOperatorName()` 读取，**不信任请求体 operator**。
3. **审计同事务**：写操作调用 `operationLogService.recordInTx(module, op, id, no, operator, detail)`（与业务同事务、失败即回滚），不要用非事务的 `record(...)`（吞异常）。
4. **乐观锁**：实体加 `@Version private Integer version;`，DDL 带 `version INT NOT NULL DEFAULT 0`；`MybatisPlusConfig` 已注册 `OptimisticLockerInnerInterceptor`（在分页插件之前），`updateById` 返回 `false` 时抛“并发冲突”提示刷新重试。
5. **库存四维键**：写库存统一走 `StockOperationService.increaseStock / decreaseStock / adjustStock(orgId, grade, productName, spec, qty)`，底层按 `org_id + product_name + spec + grade` 四维精确匹配（spec/grade 为 null 归一为空串），受 `inventory_stock.uk_stock_dims` 唯一索引约束。
6. **DDL 变更走 V2+**：新增表 / 列在 `ximu-inventory-service/src/main/resources/db/migration/` 新增 `V2__xxx.sql`，**严禁修改已 apply 的 V1**。

## 六、开源协作约定

- **构建命令**（组长统一执行，组员禁止自行跑 mvn / npm / git）：

  ```bash
  mvn clean compile -DskipTests   # 唯一正式构建命令（编译验证）
  mvn clean test                  # 全量测试（波次合并验收用）
  ```

- **单测规模**：当前 **79 条**全绿。

  | 测试类 | 条数 |
  |--------|------|
  | `AuthsTest` | 15 |
  | `OperatorContextTest` | 17 |
  | `StockAgeTest` | 5 |
  | `StockOperationServiceTest` | 21 |
  | `StockWarnTest` | 7 |
  | `DocNoGeneratorTest` | 8 |
  | `DocNoSequenceServiceTest` | 6 |

- **Flyway V1 冻结纪律**：`V1__baseline_full_schema.sql` 已作为基线（与 `schema.sql` 正文逐字节一致）；**已 apply / 已 baseline 的库绝不可改 V1**（改动触发 checksum 校验失败），后续 DDL 一律新增 `V2__...`、`V3__...`。
- **约束继承**（与 CLAUDE.md 一致）：禁止 Docker 容器（MySQL/Redis 除外）、不引 Redis 等中间件、不内置认证、禁止 git commit（统一组长提交）、`target/` 不入库。