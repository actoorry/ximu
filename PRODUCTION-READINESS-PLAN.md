# ximu 生产就绪改造计划（Production Readiness Plan）

> 状态：**进行中**。本文件记录已确认决策、改造清单、进度与验证方式，作为上线前跟踪的唯一清单。

## 一、已确认决策

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | 认证方案 | **方案 A**：新增 Spring Cloud Gateway 网关统一 JWT 校验，注入 `X-User-Id` / `X-User-Name` / `X-User-Roles` 头；下游服务从服务端上下文取操作人，不信任请求体 |
| 2 | 库存唯一维度 | **四维唯一**：`org_id + product_name + spec + grade`（明细补 `org_id` 必填、`grade` 可选，联动时 grade/spec 缺省空串匹配） |
| 3 | 架构定位 | **模块化、可插拔的开源项目**（用户拍板 2026-08-17）：独立部署多模块架构，不嵌入父应用；认证边缘可替换、预警服务可选装、DDL 管理可开关；详见 ARCHITECTURE.md |

## 二、改造清单与进度

### Phase 1 —— P0 阻断项（上线前提）

- [x] **P0-2 输入校验**：负数量/空品名拒绝
  - `StockOperationService` 兜底：`requireProduct` + 负数量抛 `IllegalArgumentException`
  - 明细实体（InboundItem/OutboundItem/CheckItem/TransferItem）加 `@NotBlank/@Positive/@PositiveOrZero`
  - 请求 DTO 加 `@Valid` 级联 + 兼容字段校验
- [x] **P0-3 删除终态拦截**：四个 `deleteWithItems` 仅允许 CREATED 状态删除，否则抛 `IllegalStateException`
- [x] **P0-4 审计日志进事务**：`record` 从 Controller 移入 Service 事务内，失败不静默吞（已落地：审计 recordInTx 与业务同事务、同成败；编辑/流转下沉 Service）
- [x] **P0-1 认证鉴权**：网关 JWT + `OperatorContext` + 操作人服务端化（不再信任请求体 operator）
  - 角色模型（5 内置）：`ADMIN/CREATOR/APPROVER/CHECKER/VIEWER`（`Role` 枚举 + `Auths` 工具 + `ForbiddenException`→403）
  - 权限矩阵 + **职责分离**（制单人不得审批/审核/完成自己的单据，ADMIN 除外）
  - 头表补 `created_by` 并在 `create` 写入 `OperatorContext.getOperatorId()`
  - 编辑/删除仅本人 CREATED 单据；库存/批号/安全库存写操作限 CHECKER/ADMIN

### Phase 2 —— P1 高风险项（首个生产版本内完成）

- [x] **P1-5 DB 连接安全**（波次1-B）：`DB_URL` 整串外置，SSL 参数由运维经环境变量控制（prod 无默认值 fail-fast；默认值仅本地兜底）
- [x] **P1-6 默认凭据移除**（波次1-B）：`application-prod.yml` 全部凭据无默认值（缺失即 `Could not resolve placeholder` 启动失败）；弱口令检测归运维侧（必设强密码）
- [x] **P1-7 消除过度绑定**：编辑接口改白名单更新 DTO，禁止绑定 `id/version/createdAt/updatedAt/checker/status`（已落地：4 个白名单 UpdateRequest DTO 替换 convertValue(Entity)；乐观锁冲突显式报错）
- [x] **P1-8 单号跨实例安全**（波次1-A）：`doc_no_seq` + `LAST_INSERT_ID(1)` 双分支原子取号（首插即返回 1），ConnectionCallback 同连接；取号随调用方事务回滚无空洞
- [x] **P1-9 库存四维唯一**：`inventory_stock` 加 `(org_id, product_name, spec, grade)` 唯一索引；`findStock` 四维匹配（spec/grade 缺省空串归一）；4 张明细表/实体/DTO 补 `org_id`(必填)/`grade`(可选)

### Phase 3 —— P2 完善项（可持续跟进）

- [ ] **P2-10 测试**：Testcontainers + 核心并发用例（并发出库不超卖）
- [x] **P2-11 Flyway**（波次2-E）：V1 基线全量 + baseline-on-migrate 存量兼容，仅 inventory-service 开迁移权
- [x] **P2-12 库龄预警落地**（波次1-C + 波次2-G）：`first_inbound_at` 读时算 `stockAgeDays`，预警动态优先/静态回退（stock_age 列保留兼容）
- [x] **P2-13 架构定位文档**（决策 3 已拍板：模块化可插拔开源项目；ARCHITECTURE.md 落地，含模块地图/插拔点清单/部署形态/扩展指南）
- [x] **P2-14 分环境配置**（波次1-B）：`application-prod.yml` ×2（无默认值样板）+ 默认 URL `characterEncoding=utf8` 修正（Connector/J 只认 Java 字符集名，`utf8mb4` 会导致 Unsupported character encoding 启动失败——冒烟测试发现并修复）
- [x] **P2-15 CORS 下沉网关**（波次1-B）：网关 `allowedOriginPatterns` 走 `CORS_ORIGINS`（逗号分隔，Binder 拆 List）；两服务保留 dev 直连用 CORS 同源同变量；运行期绑定验证列入上线 checklist

## 三、上线 Checklist

- [x] P0-1~P0-4 全部完成并通过测试（45 条单测全绿，2026-08-17）
- [x] 生产 profile 无默认密码（application-prod.yml 全部 `${DB_URL}` 无默认 fail-fast）；`useSSL`/`allowPublicKeyRetrieval` 由运维经 DB_URL 控制（prod 样板已注明）
- [x] 所有写接口有鉴权（35 处 Auths 校验点：单据流转/编辑/删除 + 库存/批号/安全库存写），操作人来自 OperatorContext
- [x] 负数量/空明细被拒绝（P0-2 校验 + StockOperationService 防御 + F 波次单测固化）
- [x] 终态单据不可删除/编辑（deleteWithItems/updateHead 状态前置校验，仅 CREATED）
- [x] 审计日志与业务同事务（recordInTx 19 处，失败即回滚）
- [x] 库存四维唯一索引（uk_stock_dims）+ 单号跨实例安全（doc_no_seq 原子取号，波次1-A）
- [x] Flyway 迁移脚本幂等可重复执行（V1 全 CREATE IF NOT EXISTS + INSERT IGNORE；V1 冻结，变更走 V2+）
- [ ] `/actuator/health` 正常，日志接入集中采集与告警
- [ ] 核心并发测试通过（不超卖）

## 四、实施顺序

```
P0-2/3/1、P1-9（已完成）→ P0-4 审计进事务（+P1-7 一并重构）
                 → P1-5/6/8 → P2 → 上线
```

## 五、本地开发与测试指引

- **直连服务开发**（不走网关）：把两服务的 `app.auth.enabled` 临时设为 `false`，即以 `dev` 身份放行。
- **走网关联调**：前端改为访问 `http://localhost:8080`（网关），请求头带 `Authorization: Bearer <token>`。
  测试令牌可用 jjwt 生成（HS256，密钥与网关 `JWT_SECRET` 一致）：
  ```java
  String token = Jwts.builder()
      .claim("userId", 1L)
      .claim("userName", "张三")
      .claim("roles", List.of("ADMIN"))
      .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
      .compact();
  ```
- **生产**：`JWT_SECRET` 必设（>=32 字节随机串），两服务 `app.auth.enabled=true`（默认），且只允许经网关访问（网络隔离，防止伪造 X-User-* 头直连）。

> 说明：P0-4 与 P1-7 都要求把编辑/流转逻辑从 Controller 下沉到 Service，且 P0-4 依赖 P0-1 提供的服务端操作人，故合并到 P0-1 之后一起做。（已完成）

## 六、角色与权限模型

| 编码 | 名称 | 职责 |
|------|------|------|
| `ADMIN` | 系统管理员 | 全部权限，可绕过职责分离 |
| `CREATOR` | 制单员 | 制单、编辑/删除本人 CREATED 单据 |
| `APPROVER` | 批准人 | 单据批准（CREATED→APPROVED） |
| `CHECKER` | 审核人/保管员 | 入库/盘点审核、调拨完成，兼库存/批号/安全库存维护 |
| `VIEWER` | 只读 | 仅查询 |

- 职责分离：`approve/check/complete` 时校验 `单据.created_by ≠ 当前操作人ID`（ADMIN 除外）
- ADMIN 旁路：`Auths.requireRole` 内置 isAdmin 短路（与另两个方法一致）——调用点漏传 `Role.ADMIN` 也不会锁死管理员（波次1 组长裁决，有单测固化）
- 角色经 JWT `roles` 声明传入，取值与 `Role` 枚举名一致（**大写**）
- 本阶段单级审批，`audit_level` 仅作业务字段记录

## 七、并行开发波次记录（组长制）

### 基线
- `mvn clean compile -DskipTests` → **BUILD SUCCESS**（5 模块全绿，覆盖网关/角色模型/P0-4/P1-7/四维库存/created_by 全部历史改动）
- 根 pom pluginManagement 钉定 maven-compiler-plugin **3.15.0**（消除 4 模块未钉版本的构建稳定性警告）
- 注意：Maven 需要写 `C:\Users\Administrator\.m2`，在文件沙箱下须以完整权限运行

### 波次1（4 路并行，进行中）
| 组员 | 任务 | 冲突面隔离 |
|------|------|-----------|
| A | P1-8 单号原子取号（doc_no_seq 表 + LAST_INSERT_ID 同连接取号 + 4 Service 换用） | schema.sql 仅末尾追加 |
| B | P1-5/6 配置硬化（DB_URL 外置 + prod profile + CORS 环境变量 + README 生产节） | 仅 yml/README/根 pom 无 |
| C | P2 库存库龄（first_inbound_at + 读接口 stockAgeDays + 种子演示数据） | schema.sql 仅 inventory_stock 区 |
| D | P2 测试地基（ximu-common：Auths/OperatorContext 纯单元测试 + test 依赖） | 仅 ximu-common |

### 波次1 交付记录（验收中）
- **C 库存库龄 ✅**：schema/实体/联动/读接口/测试五处齐备；遗留风险：
  - `stock_age`（静态列，恒 0）与 `stockAgeDays`（读时计算）语义并存，预警标红仍基于前者——待后续统一（波次2 议题）
  - inventory-service 缺 test 依赖（C 按约束未碰 pom）；A 的任务单已含此职责，验收时组长兜底
  - 存量库 INSERT IGNORE 幂等：已存在种子行不会回填 first_inbound_at，需重建表或手动 UPDATE
- **A 单号原子取号 ✅**：`doc_no_seq` 表 + `DocNoSequenceService`（`LAST_INSERT_ID(1)` 双分支原子模式，
  ConnectionCallback 保证同连接；A 修正了任务单 SQL 的首插缺陷——`VALUES(?,1)` 不设 LAST_INSERT_ID 且连接池可能读到陈旧值）；
  4 服务换用完毕（DOCNO_LOCK/DATE_FMT/today() 清除干净，import 4/4）；inventory pom 补 spring-boot-starter-test；
  语义：取号随调用方事务回滚（无空洞）；synchronized 仅单机优化，多实例原子性由 DB 行锁保证
- **F 服务层单测 ✅（波次2）**：StockOperationServiceTest 21 例（四维联动全分支 + verifyNoInteractions 防御断言）+
  DocNoSequenceServiceTest 6 例（ConnectionCallback 全路径 + seq_key 绑定校验）；疑点记录：next() 的 null 兜底为死代码、adjust 实盘 0 新建零库存行（行为观察，未改）
- **E Flyway 迁移 ✅（波次2）**：V1 基线（与 schema.sql 正文逐字节一致，组长独立 diff + E 字节校验双重印证）+
  inventory 独占迁移权（pom flyway-core/flyway-mysql，baseline-on-migrate 对存量库打 baseline 跳过 V1）+ safe-stock 显式 false 防双写；
  **V1 冻结原则**：已应用/已 baseline 的库绝不可改 V1，后续 DDL 一律 V2__…（checksum 校验）
- **G 预警语义统一 ✅（波次2）**：`InventoryStock.isWarn(stockAgeDays, stockAge, ageWarnDays)` 静态纯函数（动态优先/静态回退/阈值缺失 false），
  控制器委托 + 回填顺序修正（先 stockAgeDays 后 warn）；行为变化：firstInboundAt 达阈值的行 warn 由恒 false 变 true（修复核心）；StockWarnTest 7 例
- **B 配置硬化 ✅**：DB_URL 外置（默认值顺手修 utf8mb4）+ 2 个 application-prod.yml（无默认值 fail-fast）+
  CORS_ORIGINS 统一（B 纠正了任务单"单列表项"写法缺陷，改用逗号标量串——Spring Binder 标准 List 拆分）+ README 生产部署节
  （变量表/启动顺序/健康检查/网络隔离警告）；网关 allowedOriginPatterns 运行期绑定待部署时起服务验证（已列入上线 checklist）
- **D 测试地基 ✅**：32 条用例（AuthsTest 15 + OperatorContextTest 17），大小写敏感契约固化；
  - 报告疑点「requireRole 缺 ADMIN 旁路」→ **组长裁决：已补旁路**（静态核查 21 处生产调用点均显式带 ADMIN，行为零变化，纯防御），
    同步修正固化用例；requireNotSelfOrAdmin 补 id 判空
- 验收纪律：子 Agent 禁跑 mvn/git；编译与测试由组长在波次合并后统一执行；git 由组长按任务进度分批提交（用户授权，不推送）

### 波次2 合并验收（✅ 2026-08-17）
- `mvn clean test` → **BUILD SUCCESS**（5 模块）+ **79 条测试全绿**
  （AuthsTest 15 / OperatorContextTest 17 / StockAgeTest 5 / StockOperationServiceTest 21 / StockWarnTest 7 / DocNoGeneratorTest 8 / DocNoSequenceServiceTest 6）
- 组长修复：F 的 Mockito `any()` 未定型导致 BaseMapper 重载歧义（4 处改 `any(InventoryStock.class)`）
- 测试环境备忘（沙箱内）：Mockito inline mock maker 需 `MAVEN_OPTS=-Djdk.attach.allowAttachSelf=true`（免 spawn 外部进程）；正式 CI/本机无此限制
- 至此 **P2-11 Flyway / P2-12 库龄预警 / 服务层单测地基 全部完成并通过编译+测试验收**

### 波次1 合并验收（✅ 2026-08-17）
- `mvn clean test` → **BUILD SUCCESS**（5 模块）+ **45 条测试全绿**（AuthsTest 15 / OperatorContextTest 17 / StockAgeTest 5 / DocNoGeneratorTest 8）
- 构建环境备忘：Maven 需 .m2 写权限（沙箱内用 `-Dmaven.repo.local=工作区` 规避）；surefire fork 的 cmd.exe 管道被沙箱禁，需 `-DforkCount=0` 进程内跑测试；后续正式 CI/本机无此限制
- 至此 **P1-5 / P1-6 / P1-8 / P2 库龄 / P2 测试地基 全部完成并通过编译+测试验收**

- **H 架构定位文档 ✅（波次3）**：ARCHITECTURE.md 155 行（定位声明/模块地图/可插拔点清单 5 项/部署形态/扩展指南 6 步/开源协作约定）+ README 架构定位小节；
  组长事实抽查通过：app.auth.enabled 头契约开关（yml+Filter @Value）、单测 79 条分布表、V1 冻结纪律均与实况一致

### 并发不超卖修复（✅ 2026-08-17，组长执行，P2-10 核心）
- 实证超卖 bug：JDBC 并发程序（真实 MySQL）复现——2 线程各扣 8、库存 10，乐观锁使第二个 updateById 返回 0 行，但 StockOperationService.decreaseStock 未检查返回值，导致冲突线程静默成功，两张出库单都 APPROVED、库存只扣一次（超卖）
- 修复：increaseStock/decreaseStock/adjustStock 更新分支加 updateById(...) == 0 抛 IllegalStateException（库存并发冲突，请重试）；冲突单随调用方事务（outbound.approve）整体回滚
- 验证：修复后并发程序 success=1 conflict=1、库存=2 不为负（冲突单抛异常回滚）；StockOperationServiceTest 21→24 例（新增 3 例乐观锁冲突抛异常），全量 82 测试全绿
- 注意：Testcontainers 容器级并发压测仍依赖 Docker/CI（沙箱内 docker 不可见），本次为真实 MySQL 进程级并发验证 + 单测固化

### 首次启动冒烟（✅ 2026-08-17，组长执行）
- **真实 MySQL 8.0 上成功启动 + Flyway V1 执行成功**（`now at version v1`，297 行 DDL 首次真机验证通过）——这是项目第一次被证明"运行正确"，此前仅编译+单测
- 接口冒烟全通过：health=UP；无身份头 → HTTP 401（RBAC 拦截）；带 ADMIN 头列表 → 200 + V1 种子数据；POST 建单 → `IN20260817001`（原子取号真机工作）+ 库存联动建行 + 库龄预警真实触发（电解铜 16 天>15 阈值 warn=true）
- **发现并修复真实 bug**：`characterEncoding=utf8mb4` 是非法值（Connector/J 8.x 只认 Java 字符集名），启动即 `Unsupported character encoding`；改为 `characterEncoding=utf8`（MySQL 8 服务端默认即 utf8mb4）。4 处：两服务 application.yml + README + 本计划文档。**该 bug 编译/单测均无法发现，仅真机启动暴露**
- 构建加速：新增 `mvn-settings.xml`（阿里云 central 镜像），下载从 3~17 KB/s 提升至 165 KB/s~2.7 MB/s
- 冒烟固化：新增 `scripts/smoke-test.ps1`（打包→全新库启动→Flyway 迁移→健康轮询→401 拦截→建单取号→库存联动→停服，5 步断言），语法校验通过，各断言与手动冒烟逐条一致

### 组长终验结论（2026-08-17）
- 改造清单中 **P0 全部、P1 全部、P2 除 P2-10/P2-13 外全部完成**，均有代码 + 测试 + 构建证据
- **P2-10 Testcontainers 并发测试**：Mockito 层单测已补（47 例）；容器级并发用例需 Docker/CI 环境执行，本环境无法验证，移交 CI
- **P2-13 架构定位文档**：依赖决策 3（独立部署 or 嵌入父应用），待业务拍板，不在本次范围
- 剩余上线前人工事项：起网关验证 CORS 运行期绑定（见第三节 checklist）、存量库 ALTER created_by/first_inbound_at 或重建