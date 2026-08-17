-- ============================================================================
-- V4 数据完整性加固（P2-2 / P2-5）
--   P2-2: inventory_stock 数量列 NOT NULL DEFAULT 0 + CHECK 非负（数据库层最后一道防线）
--   P2-5: outbound 车牌/司机字段加长（新能源车牌 8 位、少数民族姓名）
-- 说明：MySQL 8.0.16+ 强制 CHECK；存量 NULL 值先回填为 0 再改列。
-- ============================================================================

UPDATE inventory_stock SET actual_qty = 0 WHERE actual_qty IS NULL;
UPDATE inventory_stock SET transit_qty = 0 WHERE transit_qty IS NULL;

ALTER TABLE inventory_stock
    MODIFY COLUMN actual_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '实际库存（账本，非负）',
    MODIFY COLUMN transit_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '在途数量（非负）',
    ADD CONSTRAINT chk_stock_qty_nonneg CHECK (actual_qty >= 0 AND transit_qty >= 0);

ALTER TABLE inventory_outbound
    MODIFY COLUMN plate_no VARCHAR(16) DEFAULT NULL,
    MODIFY COLUMN driver VARCHAR(32) DEFAULT NULL;
