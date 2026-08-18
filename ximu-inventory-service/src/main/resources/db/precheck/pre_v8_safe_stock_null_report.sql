-- ============================================================================
-- V8 迁移前置核对脚本（P1-5：safe_stock 三维键 NOT NULL 化前的存量核对）
-- ----------------------------------------------------------------------------
-- 用法：升级到 V8 之前手工执行（只读查询，不修改数据）：
--       mysql -uroot -p ximu -t < pre_v8_safe_stock_null_report.sql
-- 背景：V8 将 inventory_safe_stock 的 org_id/product_name 改 NOT NULL、material 改
--       NOT NULL DEFAULT ''，并加 (org_id, product_name, material) 唯一键。
--       存量 NULL 维度行会让 NOT NULL 化失败（迁移失败暴露，优于静默篡改）。
--       NULL org_id/product_name 的配置行无法定位归属组织，无安全回填值，
--       需人工确认（补全维度 or 删除废行）后再执行迁移。
-- ============================================================================

-- 1. 将阻断 V8 NOT NULL 化的 NULL 维度行（逐行人工处理）
SELECT id, product_name, material, org_id, service_level, z_value, version, created_at
FROM inventory_safe_stock
WHERE org_id IS NULL OR product_name IS NULL
ORDER BY id;

-- 2. 现有三行组合的重复情况（加唯一键前确认无重复；重复行需人工合并保留一条）
SELECT org_id, product_name, material, COUNT(*) AS cnt
FROM inventory_safe_stock
WHERE org_id IS NOT NULL AND product_name IS NOT NULL
GROUP BY org_id, product_name, material
HAVING COUNT(*) > 1;
