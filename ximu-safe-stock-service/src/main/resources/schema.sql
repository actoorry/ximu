-- ============================================================
-- safe-stock-service schema：1 张表 + 种子数据（DDL 参考文档）
-- 幂等：CREATE TABLE IF NOT EXISTS + 先 DELETE 业务键再 INSERT
-- 注意：spring.sql.init.mode 默认 never，本文件不会自动执行；
--       开发环境可临时改为 always 自动建表+种子，生产环境必须用 Flyway/Liquibase 或手动管理 DDL。
-- ============================================================

-- 安全库存配置
CREATE TABLE IF NOT EXISTS inventory_safe_stock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_name VARCHAR(128) DEFAULT NULL,
    material VARCHAR(128) DEFAULT NULL,
    org_id BIGINT DEFAULT NULL,
    service_level DECIMAL(5,2) DEFAULT NULL COMMENT '有货率（%）',
    z_value DECIMAL(6,3) DEFAULT NULL COMMENT 'Z值',
    replenish_cycle INT DEFAULT NULL COMMENT '补货周期（天）',
    economic_qty DECIMAL(18,4) DEFAULT NULL,
    order_point_qty DECIMAL(18,4) DEFAULT NULL,
    max_qty DECIMAL(18,4) DEFAULT NULL,
    safe_stock DECIMAL(18,4) DEFAULT NULL,
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 种子数据（真实业务：电解铜 A/B 级、铜管 Φ20mm 等）
-- ============================================================
DELETE FROM inventory_safe_stock WHERE product_name IN ('电解铜 A级','铜管 Φ20mm','电解铜 B级');
INSERT INTO inventory_safe_stock
    (product_name, material, org_id, service_level, z_value, replenish_cycle, economic_qty, order_point_qty, max_qty, safe_stock)
VALUES
    ('电解铜 A级', '阴极铜板', 1, 95.00, 1.645,  7, 1000.0000, 500.0000, 3000.0000, 200.0000),
    ('铜管 Φ20mm',  '紫铜管',   1, 98.00, 2.054,  5,  500.0000, 200.0000, 1500.0000, 100.0000),
    ('电解铜 B级', '阴极铜板', 2, 90.00, 1.282, 10,  800.0000, 400.0000, 2500.0000, 150.0000);
