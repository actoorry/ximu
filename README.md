# ximu 进销存管理系统

> 基于 Spring Boot 微服务架构的进销存库存管理模块，覆盖入库、出库、盘点、调拨、安全库存五大核心业务。

## 项目介绍

ximu 是一个进销存管理系统，聚焦库存域的业务闭环。系统围绕「单据」展开——每张入库单、出库单、盘点单、调拨单都遵循「制单 → 批准 → 审核」的流转流程，单据流转到终态时自动联动实时库存，保证账实一致。

系统采用微服务架构，按业务耦合度拆分为两个独立服务：库存操作服务负责操作同一库存账本的入库、出库、盘点、调拨，安全库存服务负责阈值管理与补货策略。两者独立部署、独立扩容。

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
| 框架 | Spring Boot 3.2.5 |
| 数据访问 | MyBatis-Plus 3.5.9 |
| 数据库 | MySQL 8 |
| 构建工具 | Maven |
| 辅助 | Lombok、Spring Validation、Spring AOP、Spring Boot Actuator |

## 快速开始

环境要求：JDK 17、Maven 3.9+、MySQL 8。

1. 初始化数据库：执行各服务 `src/main/resources/schema.sql` 建表并写入种子数据。
2. 配置数据库连接：通过环境变量 `DB_USERNAME` / `DB_PASSWORD` 注入账号密码（默认 `root` / `123456`），或直接修改各服务的 `application.yml`。
3. 构建：

```bash
mvn clean package -DskipTests
```

4. 启动两个服务：

```bash
java -jar ximu-inventory-service/target/ximu-inventory-service-1.0-SNAPSHOT.jar
java -jar ximu-safe-stock-service/target/ximu-safe-stock-service-1.0-SNAPSHOT.jar
```

5. 访问接口：

- 库存操作服务：`http://localhost:8081/api/inventory/{inbound|outbound|check|transfer|stock|batch|log}`
- 安全库存服务：`http://localhost:8082/api/safe-stock`
- 健康检查：`http://localhost:8081/actuator/health`

统一返回结构：`{ "code": 0, "message": "ok", "data": {} }`，`code=0` 表示成功；分页返回 `{ "list": [...], "total": N }`。

## 目录结构

```
ximu
├── ximu-common                公共模块（统一返回结构、分页基类）
├── ximu-inventory-service     库存操作微服务（端口 8081）
├── ximu-safe-stock-service    安全库存微服务（端口 8082）
└── pom.xml                    父工程（依赖版本管理）
```

## 版本

1.0-SNAPSHOT
