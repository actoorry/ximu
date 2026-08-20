-- ============================================================================
-- V10 删列（R2-P2-6：调拨按「同组织库位移库」定案不联动库存 + transit_qty/stock_age 为死字段）
--   transit_qty（在途数量）：newStock 恒置 0、业务代码零更新，为历史遗留字段；
--   stock_age（静态库龄）：newStock 恒置 0，读侧预警已由 first_inbound_at 动态计算 stockAgeDays，死字段。
--
-- 前置核对：先跑 db/precheck/pre_v10_transit_qty_report.sql，确认 transit_qty 无非零存量
--           （预期 0 行）后再执行本迁移，避免删除「仍承载在途语义」的存量数据。
-- ============================================================================

-- MySQL 8 不允许 DROP 被 CHECK 约束引用的列：先删 chk_stock_qty_nonneg（含 transit_qty 比较）
ALTER TABLE inventory_stock DROP CHECK chk_stock_qty_nonneg;

-- 删除两列（transit_qty 在途 / stock_age 遗留静态库龄）
ALTER TABLE inventory_stock
    DROP COLUMN transit_qty,
    DROP COLUMN stock_age;

-- 恢复 actual_qty 非负约束（账本最后一道防线保留，不再含 transit_qty）
ALTER TABLE inventory_stock
    ADD CONSTRAINT chk_stock_qty_nonneg CHECK (actual_qty >= 0);
