-- ============================================================================
-- V7 迁移前置核对脚本（P1-2：明细数量约束加列前的存量数据核对）
-- ----------------------------------------------------------------------------
-- 用法：升级到 V7 之前手工执行（只读查询，不修改数据）：
--       mysql -uroot -p ximu -t < pre_v7_item_qty_report.sql
-- 背景：V7 会将五张明细表的数量列改为 NOT NULL/加非负 CHECK。
--       - NULL 行由迁移自动回填 0（联动语义不变，无需核对）；
--       - 负值行会阻断 CHECK 添加导致迁移失败（失败暴露优于静默篡改），
--         必须先按下方清单人工修正（负数明细多为直改库残留，需业务确认真实数量）。
-- ============================================================================

-- 1. 负值明细行（将阻断 V7 的 CHECK 约束，需人工修正后再迁移）
SELECT 'inbound_item' AS 表, id, inbound_id AS head_id, qty, NULL AS other_qty FROM inbound_item WHERE qty < 0 OR settle_qty < 0
UNION ALL
SELECT 'outbound_item', id, outbound_id, qty, NULL FROM outbound_item WHERE qty < 0
UNION ALL
SELECT 'transfer_item', id, transfer_id, qty, NULL FROM transfer_item WHERE qty < 0
UNION ALL
SELECT 'check_item', id, check_id, book_qty, actual_qty FROM check_item WHERE book_qty < 0 OR actual_qty < 0;

-- 2. NULL 明细行（迁移将回填 0，此处仅供留档确认无意外数据）
SELECT 'inbound_item.qty' AS 列, COUNT(*) AS null_rows FROM inbound_item WHERE qty IS NULL
UNION ALL SELECT 'inbound_item.settle_qty(保留NULL)', COUNT(*) FROM inbound_item WHERE settle_qty IS NULL
UNION ALL SELECT 'outbound_item.qty', COUNT(*) FROM outbound_item WHERE qty IS NULL
UNION ALL SELECT 'transfer_item.qty', COUNT(*) FROM transfer_item WHERE qty IS NULL
UNION ALL SELECT 'check_item.book_qty', COUNT(*) FROM check_item WHERE book_qty IS NULL
UNION ALL SELECT 'check_item.actual_qty(保留NULL)', COUNT(*) FROM check_item WHERE actual_qty IS NULL;
