-- ============================================================================
-- V4 数据完整性加固（P2-2 / P2-5）
--   P2-2: inventory_stock 数量列 NOT NULL DEFAULT 0 + CHECK 非负（数据库层最后一道防线）
--   P2-5: outbound 车牌/司机字段加长（新能源车牌 8 位、少数民族姓名）
-- 说明：MySQL 8.0.16+ 强制 CHECK；存量 NULL 值先回填为 0 再改列。
-- ============================================================================

UPDATE inventory_stock SET actual_qty = 0 WHERE actual_qty IS NULL;
UPDATE inventory_stock SET transit_qty = 0 WHERE transit_qty IS NULL;

-- 存量负值处置：负库存为历史异常（超卖残留/直改），CHECK 约束会拒绝负值导致迁移失败。
-- 此处显式归零保证迁移不失败；上线后务必人工核对 inventory_stock 账实（负值归零会掩盖真实差异）。
UPDATE inventory_stock SET actual_qty = 0 WHERE actual_qty < 0;
UPDATE inventory_stock SET transit_qty = 0 WHERE transit_qty < 0;

ALTER TABLE inventory_stock
    MODIFY COLUMN actual_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '实际库存（账本，非负）',
    MODIFY COLUMN transit_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '在途数量（非负）',
    ADD CONSTRAINT chk_stock_qty_nonneg CHECK (actual_qty >= 0 AND transit_qty >= 0);

ALTER TABLE inventory_outbound
    MODIFY COLUMN plate_no VARCHAR(16) DEFAULT NULL,
    MODIFY COLUMN driver VARCHAR(32) DEFAULT NULL;
