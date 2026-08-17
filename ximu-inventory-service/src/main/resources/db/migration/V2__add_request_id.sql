-- ============================================================================
-- V2 幂等键：四张单据头表增加 request_id（客户端幂等键，防双击/重试重复建单）
-- 说明：request_id 由客户端生成（UUID 或业务流水），唯一索引保证同一次提交只落一条单据。
--       存量行 request_id 为 NULL（MySQL 唯一索引允许 NULL 多行共存）。
-- ============================================================================

ALTER TABLE inventory_inbound  ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键' AFTER audit_level, ADD UNIQUE KEY uk_inbound_request_id (request_id);
ALTER TABLE inventory_outbound ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键' AFTER driver_phone, ADD UNIQUE KEY uk_outbound_request_id (request_id);
ALTER TABLE inventory_check    ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键' AFTER batch_no,   ADD UNIQUE KEY uk_check_request_id (request_id);
ALTER TABLE inventory_transfer ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键' AFTER batch_no,   ADD UNIQUE KEY uk_transfer_request_id (request_id);

