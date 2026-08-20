-- ============================================================================
-- V4 迁移前置核对脚本（P0-4：负值归零前先导出差异清单，人工核对后再执行 V4）
-- ----------------------------------------------------------------------------
-- 用法：存量库升级到 V4 之前手工执行（只读查询，不修改数据）：
--       mysql -uroot -p ximu -t < pre_v4_negative_stock_report.sql
-- 背景：V4 会将 inventory_stock 的负值/NULL 数量静默归零（CHECK 约束前置回填）。
--       归零会掩盖真实账实差异，必须先导出下列清单逐行核对：
--       - 负值行：确认是超卖残留还是直改错误，业务侧决定是否补出库红冲；
--       - NULL 行：确认是历史脏数据还是新维度行，决定回填 0 是否符合预期。
-- 核对通过后再让服务执行 V4 迁移；清单保留存档（审计留痕）。
-- ============================================================================

-- 1. 将被 V4 归零的负值行（actual_qty / transit_qty < 0）
--    R2-P1-6：V4 时代 inventory_stock 尚无 material 列（V5 才加五维），
--    此处不 SELECT material，否则在 V4 前核对时脚本因 Unknown column 报错
SELECT id, org_id, product_name, spec, grade,
       actual_qty, transit_qty, version, created_at, updated_at,
       '负值 → 将被归零为 0' AS action
FROM inventory_stock
WHERE actual_qty < 0 OR transit_qty < 0;

-- 2. 将被 V4 回填的 NULL 行
SELECT id, org_id, product_name, spec, grade,
       actual_qty, transit_qty, version, created_at, updated_at,
       'NULL → 将被回填为 0' AS action
FROM inventory_stock
WHERE actual_qty IS NULL OR transit_qty IS NULL;

-- 3. 汇总计数（预期核对行数 = 两类之和）
SELECT COUNT(*) AS rows_to_normalize
FROM inventory_stock
WHERE actual_qty < 0 OR transit_qty < 0 OR actual_qty IS NULL OR transit_qty IS NULL;
