-- ============================================================================
-- V3 安全库存配置表：safe-stock-service 依赖的 inventory_safe_stock 表。
-- 说明：迁移权归 inventory-service（独占 ximu 库 DDL），safe-stock 显式关闭 Flyway；
--       此前该表无自动建表途径（P1-5），现纳入 inventory 迁移统一管理。
-- ============================================================================

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

DELETE FROM inventory_safe_stock WHERE product_name IN ('电解铜 A级','铜管 Φ20mm','电解铜 B级');
INSERT INTO inventory_safe_stock
    (product_name, material, org_id, service_level, z_value, replenish_cycle, economic_qty, order_point_qty, max_qty, safe_stock)
VALUES
    ('电解铜 A级', '阴极铜板', 1, 95.00, 1.645,  7, 1000.0000, 500.0000, 3000.0000, 200.0000),
    ('铜管 Φ20mm',  '紫铜管',   1, 98.00, 2.054,  5,  500.0000, 200.0000, 1500.0000, 100.0000),
    ('电解铜 B级', '阴极铜板', 2, 90.00, 1.282, 10,  800.0000, 400.0000, 2500.0000, 150.0000);
