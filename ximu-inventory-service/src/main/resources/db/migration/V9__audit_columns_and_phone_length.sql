-- ============================================================================
-- V9 行级审计链 + 电话列放宽 + 排序规则规范（P2-5 / P2-12 / P2-11）
--   P2-5: 单据头表与基础数据表补 updated_by（最后修改人ID，应用侧 MetaObjectHandler
--         从可信登录上下文自动填充，漏写即漏审计的问题随自动填充消失）；
--         operation_log 补 operator_id——operator 姓名列可重名、可后期改名，无法作为
--         唯一审计锚点，ID 列才是（姓名列保留做展示与检索）。
--   P2-12: inventory_outbound.driver_phone VARCHAR(11) → VARCHAR(32)
--         （原长度写死大陆手机号，+86 前缀/座机分机均存不下）。
--   P2-11: 排序规则规范——自 V9 起任何新增表/字符列变更显式
--         CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci；本迁移新增列均为
--         BIGINT 数值列与 VARCHAR(32) 电话列，不引入排序规则差异；
--         存量表 collation 差异（V1 建表显式 utf8mb4_unicode_ci vs 库默认 0900_ai_ci）
--         由 DBA 在窗口期评估统一（CONVERT TO CHARACTER SET 会重写全表、锁表），
--         不在本迁移强转；应用侧已统一 JDBC connectionCollation=utf8mb4_0900_ai_ci
--         钳制连接层排序规则，跨表比较的 "Illegal mix of collations" 面已收敛。
--
-- 设计说明：
--   * 明细表不加 updated_by/操作人列——明细行的写操作恒与所属头单据同事务、同操作人，
--     加列纯属冗余（头的 updated_by 已可锚定到人）。
--   * updated_by/operator_id 对历史行为 NULL（不可追溯），不回填伪造值。
--   * 幂等性：一次性 ADD COLUMN / MODIFY，无前置核对脚本要求。
-- ============================================================================

-- 1. 四张单据头表 + 三张基础数据表：补最后修改人
ALTER TABLE inventory_inbound
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID（P2-5，应用自动填充）';
ALTER TABLE inventory_outbound
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID（P2-5，应用自动填充）';
ALTER TABLE inventory_check
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID（P2-5，应用自动填充）';
ALTER TABLE inventory_transfer
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID（P2-5，应用自动填充）';
ALTER TABLE inventory_safe_stock
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID（P2-5，应用自动填充）';
ALTER TABLE inventory_stock
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID（P2-5，应用自动填充）';
ALTER TABLE inventory_batch
    ADD COLUMN updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID（P2-5，应用自动填充）';

-- 2. 审计表：补操作人 ID 锚点（operator 姓名列保留：展示 + keyword 检索）
ALTER TABLE operation_log
    ADD COLUMN operator_id BIGINT DEFAULT NULL COMMENT '操作人用户ID（P2-5，应用自动填充）';

-- 3. P2-12：电话列放宽（纯 ASCII 内容，列定义不涉及 collation 变化）
ALTER TABLE inventory_outbound
    MODIFY COLUMN driver_phone VARCHAR(32) DEFAULT NULL COMMENT '司机电话（含国际区号/分机，最长32）';
