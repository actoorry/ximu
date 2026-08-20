-- ============================================================================
-- V10 迁移前置核对脚本（R2-P2-6：transit_qty 删列前确认无在途存量）
-- ----------------------------------------------------------------------------
-- 用法：升级到 V10 之前手工执行（只读查询，不修改数据）：
--       mysql -uroot -p ximu -t < pre_v10_transit_qty_report.sql
-- 背景：V10 删除 inventory_stock.transit_qty（在途数量）与 stock_age（遗留静态库龄）两列。
--       transit_qty 自 V4 起 NOT NULL DEFAULT 0、业务零更新（newStock 恒置 0），但存量库
--       若有历史非零值（早期手改/残留），删除即丢失——必须先导出核对。
--       stock_age 恒 0 且读侧已由 first_inbound_at 动态计算库龄，删列无数据风险。
-- 核对通过后再让服务执行 V10 迁移；非零行清单保留存档（审计留痕）。
-- ============================================================================

-- 1. transit_qty 非零存量行（预期 0 行；若非空需人工确认是数据残留还是真实在途）
SELECT id, org_id, product_name, material, spec, grade,
       actual_qty, transit_qty, version, created_at, updated_at,
       'transit_qty 非零 → V10 删列将丢失该值' AS action
FROM inventory_stock
WHERE transit_qty <> 0
ORDER BY id;

-- 2. 汇总计数（0 行即为核对通过）
SELECT COUNT(*) AS nonzero_transit_rows
FROM inventory_stock
WHERE transit_qty <> 0;
