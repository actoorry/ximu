-- ============================================================================
-- V8 safe_stock 键约束 + 单据幂等键加归属（P1-5 / P1-7）
--   P1-5: inventory_safe_stock 补 (org_id, product_name, material) 业务唯一键（此前同配置可无限重复，
--         预警计算取哪条全凭运气）+ request_id 幂等列 + created_by 审计列；
--         三维键 NOT NULL 化（NULL 组合在 MySQL 唯一索引下不受约束，防重失效）。
--   P1-7: 四张单据头表的 request_id 幂等键从「单列全局唯一」改为 (request_id, created_by) 复合唯一，
--         消除跨用户串单：此前用户 B 复用用户 A 的 requestId 会直接拿到 A 的单据内容（越权信息泄露）。
--
-- 前置核对：safe_stock 存量 NULL 维度行会让 NOT NULL 化失败（迁移失败暴露，优于静默篡改）——
--           先跑 db/precheck/pre_v8_safe_stock_null_report.sql，人工处理后再迁移。
--           单据表复合键比原单列键更宽松，存量数据不会产生新冲突，无需核对。
-- ============================================================================

-- 1. safe_stock：幂等列 + 创建人列
ALTER TABLE inventory_safe_stock
    ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键' AFTER safe_stock,
    ADD COLUMN created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID' AFTER request_id;

-- 2. safe_stock：三维键 NOT NULL 化（类型沿用 V3：VARCHAR(128) / BIGINT）
ALTER TABLE inventory_safe_stock
    MODIFY COLUMN org_id BIGINT NOT NULL COMMENT '组织ID（必填）',
    MODIFY COLUMN product_name VARCHAR(128) NOT NULL COMMENT '品名（必填）',
    MODIFY COLUMN material VARCHAR(128) NOT NULL DEFAULT '' COMMENT '物料/材质（缺省空串）';

-- 3. safe_stock：业务唯一键 + 幂等复合键（幂等键同单据表规则：同创建人内唯一，跨用户互不干扰）
ALTER TABLE inventory_safe_stock
    ADD UNIQUE KEY uk_safe_stock_dims (org_id, product_name, material),
    ADD UNIQUE KEY uk_safe_stock_request_id (request_id, created_by);

-- 4. 四张单据头表：幂等键改复合唯一（DROP 单列键 + ADD 同名复合键）
ALTER TABLE inventory_inbound
    DROP INDEX uk_inbound_request_id,
    ADD UNIQUE KEY uk_inbound_request_id (request_id, created_by);
ALTER TABLE inventory_outbound
    DROP INDEX uk_outbound_request_id,
    ADD UNIQUE KEY uk_outbound_request_id (request_id, created_by);
ALTER TABLE inventory_check
    DROP INDEX uk_check_request_id,
    ADD UNIQUE KEY uk_check_request_id (request_id, created_by);
ALTER TABLE inventory_transfer
    DROP INDEX uk_transfer_request_id,
    ADD UNIQUE KEY uk_transfer_request_id (request_id, created_by);
