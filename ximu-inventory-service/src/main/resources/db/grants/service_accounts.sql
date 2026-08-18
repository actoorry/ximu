-- ============================================================================
-- 按服务拆分 DB 账号最小授权模板（P2-8）
-- 现状问题：两服务共用同一 DB 账号（dev 默认 root/123456），safe-stock 只需
--           两张表的 DML 权限却持有全库权限——任一服务凭据泄露即整个库失守。
-- 用法：DBA 在生产按需执行（密码占位符勿填真实值进 git）；
--       应用侧通过 DB_USERNAME / DB_PASSWORD 环境变量注入对应服务账号。
-- 账号-服务映射：
--   ximu_inventory → inventory-service（业务全表 DML + Flyway 迁移 DDL 权限）
--   ximu_safestock → safe-stock-service（仅 safe_stock 表 DML + 审计表写入）
-- ============================================================================

CREATE USER 'ximu_inventory'@'%' IDENTIFIED BY '<INVENTORY_DB_PASSWORD>';
CREATE USER 'ximu_safestock'@'%' IDENTIFIED BY '<SAFESTOCK_DB_PASSWORD>';

-- inventory-service：独占迁移权（Flyway V1~V9 DDL）+ 全部业务表读写
GRANT ALL PRIVILEGES ON ximu.* TO 'ximu_inventory'@'%';

-- safe-stock-service：最小授权——配置表 DML + 审计表读写（keyword 检索）；
-- 不授予任何 DDL（迁移权归 inventory-service，防止两服务同时对同库做 schema 变更）
GRANT SELECT, INSERT, UPDATE, DELETE ON ximu.inventory_safe_stock TO 'ximu_safestock'@'%';
GRANT SELECT, INSERT ON ximu.operation_log TO 'ximu_safestock'@'%';
-- SchemaStartupCheck 启动期校验表存在性需要 information_schema 只读
GRANT SELECT ON information_schema.TABLES TO 'ximu_safestock'@'%';

FLUSH PRIVILEGES;
