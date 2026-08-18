-- ============================================================================
-- V6 清理 V1/V3 夹带的演示种子数据（P0-4）
-- 背景：V1 基线与 V3 建表时夹带了演示单据/库存/批号/安全库存种子，生产库执行迁移
--       会写入演示数据并执行破坏性 DELETE（V1~V5 因 checksum 冻结不可改，故新增本迁移清理）。
-- 清理对象：固定演示单号（IN/OUT/CK/TR 2026xxxx）+ 演示批号 + 演示 safe_stock 配置 +
--           演示操作日志（operator='系统初始化'）。
-- 幂等：全部按精确单号/键值匹配，重复执行删除 0 行。
-- 演示数据副本：db/demo/demo_seed.sql（Flyway 不加载，演示环境手工执行）。
--
-- 演示库存四行按「五维 + 数量全值精确匹配」删除（P1 复核修订，取代原"留给 DBA 人工清理"方案）：
--    仅当行的五维与 V1 种子完全一致且 actual_qty/transit_qty 仍等于种子值（即从未被业务流转改动）才命中，
--    已被流转污染的行（数量已变）不匹配、自然保留，杜绝误删业务数据，也免去 DBA 人工卡点。
--    （V1 种子建于四维时代，V5 加 material 列后种子行 material=''，与下述匹配值一致。）
-- ============================================================================

-- 1. 演示单据明细（先查明演示头 id 再删明细，与 V1 种子的 DELETE 同款结构）
DELETE FROM inbound_item WHERE inbound_id IN
    (SELECT id FROM inventory_inbound WHERE inbound_no IN ('IN20260801001','IN20260801002'));
DELETE FROM outbound_item WHERE outbound_id IN
    (SELECT id FROM inventory_outbound WHERE outbound_no IN ('OUT20260805001','OUT20260805002'));
DELETE FROM check_item WHERE check_id IN
    (SELECT id FROM inventory_check WHERE check_no IN ('CK20260810001','CK20260810002'));
DELETE FROM transfer_item WHERE transfer_id IN
    (SELECT id FROM inventory_transfer WHERE transfer_no IN ('TR20260808001','TR20260808002'));

-- 2. 演示单据头（固定演示单号，不存在与真实单据撞号的场景——项目未上生产）
DELETE FROM inventory_inbound  WHERE inbound_no  IN ('IN20260801001','IN20260801002');
DELETE FROM inventory_outbound WHERE outbound_no IN ('OUT20260805001','OUT20260805002');
DELETE FROM inventory_check    WHERE check_no    IN ('CK20260810001','CK20260810002');
DELETE FROM inventory_transfer WHERE transfer_no IN ('TR20260808001','TR20260808002');

-- 3. 演示批号（盘点/调拨演示种子引用的 BT2026080xxxx）
DELETE FROM inventory_batch WHERE batch_no IN ('BT20260801001','BT20260801002','BT20260801003');

-- 4. 演示安全库存配置（V3 种子，防删业务方预录的同结构真实配置：按 V3 种子完整键值精确匹配）
DELETE FROM inventory_safe_stock
WHERE (product_name, material, org_id, service_level, z_value) IN
    (('电解铜 A级', '阴极铜板', 1, 95.00, 1.645),
     ('铜管 Φ20mm',  '紫铜管',   1, 98.00, 2.054),
     ('电解铜 B级', '阴极铜板', 2, 90.00, 1.282));

-- 5. 演示库存四行（全值精确匹配：五维 + 数量与 V1 种子完全一致才删，被动过的行保留）
DELETE FROM inventory_stock WHERE (org_id, product_name, material, spec, grade, actual_qty, transit_qty) IN
    ((1, '电解铜 A级', '', '99.99%', 'A级',  3000.0000, 1000.0000),
     (1, '铜管',        '', 'Φ20mm',  '合格',  900.0000,    0.0000),
     (2, '电解铜 B级', '', '99.95%', 'B级',   700.0000,  100.0000),
     (1, '铜板 1.5mm', '', '1.5mm',   '合格',  500.0000,    0.0000));

-- 6. 演示操作日志（V1 种子标记 operator='系统初始化'，且 target_no 为演示单号）
DELETE FROM operation_log
WHERE operator = '系统初始化'
  AND target_no IN ('IN20260801001','OUT20260805001');
