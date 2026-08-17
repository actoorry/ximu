-- ============================================================================
-- V5 库存唯一键五维化（P2-1：加 material 维度）
--   inventory_stock 加 material 列（回填空串），唯一键由四维改五维。
-- ============================================================================

ALTER TABLE inventory_stock ADD COLUMN material VARCHAR(128) NOT NULL DEFAULT '' COMMENT '物料/材质（库存五维之一）' AFTER grade;

-- 唯一键由 (org_id, product_name, spec, grade) 改为 (org_id, product_name, material, spec, grade)
ALTER TABLE inventory_stock DROP INDEX uk_stock_dims;
ALTER TABLE inventory_stock ADD UNIQUE KEY uk_stock_dims (org_id, product_name, material, spec, grade);
