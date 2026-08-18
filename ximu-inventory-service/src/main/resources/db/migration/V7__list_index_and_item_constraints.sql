-- ============================================================================
-- V7 列表页索引 + 明细表数量约束（P1-1 / P1-2）
--   P1-1: 四张头表补 (status, created_at) 组合索引 + operation_log 查询索引，
--         覆盖列表页最高频的「状态过滤 + created_at 倒序分页」查询（此前仅单号唯一键，全表扫+filesort）；
--         operation_log 的 detail TEXT 列模糊查询在 Java 层同步下线（OperationLogService）。
--   P1-2: 五张明细表数量列补 NOT NULL/非负 CHECK（V4 只保护了 inventory_stock，明细层此前无约束）。
--
-- 语义决策（相对修复方案.md 概要的修订，见该文档 P1-2 节）：
--   * qty / book_qty：NULL 回填 0 + NOT NULL DEFAULT 0 + CHECK >= 0
--     ——null 在库存联动中本就"按 0 跳过"，回填不改变联动结果；
--   * settle_qty / actual_qty：**保留 NULL**，仅加条件 CHECK
--     ——settle_qty NULL=未结算（联动回退按 qty 计）/ 0=结算为零，actual_qty NULL=未盘（审核时
--       Java 层强制非空）/ 0=实盘为零（盘亏到零），若回填 0 会把"未结算/未盘"静默变成
--       "结算为零/盘亏到零"，属于账目语义陷阱，故不做 NOT NULL 化。
--   * CHECK 取 >= 0 而非 > 0：回填产生的 0 值行需要放行；防负数（联动会反向增减库存）才是核心目标，
--     0 值明细本身无账务危害，正向约束由 API 层 @Positive 承担。
-- 前置核对：存量负值行会阻断 CHECK 添加（迁移失败暴露，优于静默篡改）——
--           先跑 db/precheck/pre_v7_item_qty_report.sql，人工修正负值后再迁移。
-- ============================================================================

-- 1. 四张头表组合索引（status 等值过滤 + created_at 排序，最左前缀匹配列表页查询形态）
ALTER TABLE inventory_inbound  ADD INDEX idx_inbound_status_created  (status, created_at);
ALTER TABLE inventory_outbound ADD INDEX idx_outbound_status_created (status, created_at);
ALTER TABLE inventory_check    ADD INDEX idx_check_status_created    (status, created_at);
ALTER TABLE inventory_transfer ADD INDEX idx_transfer_status_created (status, created_at);

-- 2. 审计表查询索引（module/operation/target_id 精确过滤 + created_at 排序分页）
ALTER TABLE operation_log ADD INDEX idx_oplog_module_op_target_created (module, operation, target_id, created_at);

-- 3. 明细数量列 NULL 回填（联动语义不变：null 本就按 0 处理）
UPDATE inbound_item  SET qty = 0 WHERE qty IS NULL;
UPDATE outbound_item SET qty = 0 WHERE qty IS NULL;
UPDATE transfer_item SET qty = 0 WHERE qty IS NULL;
UPDATE check_item    SET book_qty = 0 WHERE book_qty IS NULL;

-- 4. 明细数量约束（NOT NULL + 非负 CHECK；settle_qty/actual_qty 保留 NULL 语义见文件头）
ALTER TABLE inbound_item
    MODIFY COLUMN qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '数量（非负，正向由 API 层校验）',
    ADD CONSTRAINT chk_inbound_item_qty_nonneg CHECK (qty >= 0),
    ADD CONSTRAINT chk_inbound_item_settle_nonneg CHECK (settle_qty IS NULL OR settle_qty >= 0);

ALTER TABLE outbound_item
    MODIFY COLUMN qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '数量（非负，正向由 API 层校验）',
    ADD CONSTRAINT chk_outbound_item_qty_nonneg CHECK (qty >= 0);

ALTER TABLE transfer_item
    MODIFY COLUMN qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '数量（非负，正向由 API 层校验）',
    ADD CONSTRAINT chk_transfer_item_qty_nonneg CHECK (qty >= 0);

ALTER TABLE check_item
    MODIFY COLUMN book_qty DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '账面数量（非负快照）',
    ADD CONSTRAINT chk_check_item_qty_nonneg CHECK (book_qty >= 0 AND (actual_qty IS NULL OR actual_qty >= 0));
