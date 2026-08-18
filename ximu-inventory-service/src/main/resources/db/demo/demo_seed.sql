-- ============================================================================
-- 演示环境种子数据（P0-4：从 V1/V3 迁移剥离后的唯一演示数据来源）
-- ----------------------------------------------------------------------------
-- 用法：Flyway 迁移（V1~V6+）完成后手工执行
--       mysql -uroot -p ximu < demo_seed.sql
-- 幂等：头表/库存/批号 INSERT IGNORE（唯一键兜底）；明细、safe_stock 与日志先删后插；
--       重复执行安全，但请勿对有真实业务数据的库执行。
-- 注意：位于 db/demo/ 目录，Flyway locations（classpath:db/migration）不会加载；
--       库存行 material 取值与明细种子对齐，避免五维裂变（见错误清单 P1-4）。
-- ============================================================================

-- 入库头种子（2 条）
INSERT IGNORE INTO inventory_inbound
    (inbound_no, inbound_type, source_order_no, status, created_by, checker, audit_level)
VALUES
    ('IN20260801001', '估价', 'PO20260730001', 'CHECKED',  1, '张三', '总监审核'),
    ('IN20260801002', '代销', 'PO20260730002', 'APPROVED', 1, NULL,   '经理审核');

-- 入库明细种子（各 2 条；幂等：先删关联头明细，再插）
DELETE FROM inbound_item WHERE inbound_id IN
    (SELECT id FROM inventory_inbound WHERE inbound_no IN ('IN20260801001','IN20260801002'));
INSERT INTO inbound_item (inbound_id, org_id, product_name, grade, material, spec, qty, settle_qty) VALUES
    ((SELECT id FROM inventory_inbound WHERE inbound_no='IN20260801001'), 1, '电解铜 A级', 'A级',  '电解铜', '99.99%', 5000.0000, 5000.0000),
    ((SELECT id FROM inventory_inbound WHERE inbound_no='IN20260801001'), 1, '铜管',        '合格', '铜',     'Φ20mm',  1200.0000, 1200.0000),
    ((SELECT id FROM inventory_inbound WHERE inbound_no='IN20260801002'), 2, '电解铜 B级', 'B级',  '电解铜', '99.95%', 800.0000,  800.0000),
    ((SELECT id FROM inventory_inbound WHERE inbound_no='IN20260801002'), 1, '铜板 1.5mm', '合格', '铜',     '1.5mm',  300.0000,  300.0000);

-- 出库头种子（2 条）
INSERT IGNORE INTO inventory_outbound
    (outbound_no, sale_order_no, freight_bearer, carrier, plate_no, driver, driver_phone, status, created_by)
VALUES
    ('OUT20260805001', 'SO20260804001', '博宇承担', '顺丰物流', '京A12345', '王武', '13800138000', 'APPROVED', 1),
    ('OUT20260805002', 'SO20260804002', '对方承担', '德邦物流', '京B67890', '李四', '13900139000', 'CREATED',  1);

-- 出库明细种子（各 2 条）
DELETE FROM outbound_item WHERE outbound_id IN
    (SELECT id FROM inventory_outbound WHERE outbound_no IN ('OUT20260805001','OUT20260805002'));
INSERT INTO outbound_item (outbound_id, org_id, product_name, grade, material, spec, qty) VALUES
    ((SELECT id FROM inventory_outbound WHERE outbound_no='OUT20260805001'), 1, '电解铜 A级', 'A级',  '电解铜', '99.99%', 2000.0000),
    ((SELECT id FROM inventory_outbound WHERE outbound_no='OUT20260805001'), 1, '铜管',        '合格', '铜',     'Φ20mm',  300.0000),
    ((SELECT id FROM inventory_outbound WHERE outbound_no='OUT20260805002'), 2, '电解铜 B级', 'B级',  '电解铜', '99.95%', 100.0000),
    ((SELECT id FROM inventory_outbound WHERE outbound_no='OUT20260805002'), 1, '铜板 1.5mm', '合格', '铜',     '1.5mm',  50.0000);

-- 盘点头种子（2 条）
INSERT IGNORE INTO inventory_check
    (check_no, batch_no, status, created_by)
VALUES
    ('CK20260810001', 'BT20260801001', 'CHECKED',  1),
    ('CK20260810002', 'BT20260801002', 'APPROVED', 1);

-- 盘点明细种子（第1条2行、第2条1行）
DELETE FROM check_item WHERE check_id IN
    (SELECT id FROM inventory_check WHERE check_no IN ('CK20260810001','CK20260810002'));
INSERT INTO check_item (check_id, org_id, product_name, grade, material, spec, book_qty, actual_qty) VALUES
    ((SELECT id FROM inventory_check WHERE check_no='CK20260810001'), 1, '电解铜 A级', 'A级',  '电解铜', '99.99%', 5000.0000, 4998.0000),
    ((SELECT id FROM inventory_check WHERE check_no='CK20260810001'), 1, '铜管',        '合格', '铜',     'Φ20mm',  1200.0000, 1200.0000),
    ((SELECT id FROM inventory_check WHERE check_no='CK20260810002'), 2, '电解铜 B级', 'B级',  '电解铜', '99.95%', 800.0000,  800.0000);

-- 调拨头种子（2 条）
INSERT IGNORE INTO inventory_transfer
    (transfer_no, batch_no, status, created_by)
VALUES
    ('TR20260808001', 'BT20260801001', 'COMPLETED', 1),
    ('TR20260808002', 'BT20260801002', 'APPROVED', 1);

-- 调拨明细种子（第1条2行、第2条1行）
DELETE FROM transfer_item WHERE transfer_id IN
    (SELECT id FROM inventory_transfer WHERE transfer_no IN ('TR20260808001','TR20260808002'));
INSERT INTO transfer_item (transfer_id, org_id, product_name, grade, material, spec, qty, target_location) VALUES
    ((SELECT id FROM inventory_transfer WHERE transfer_no='TR20260808001'), 1, '电解铜 A级', 'A级',  '电解铜', '99.99%', 1000.0000, '华东仓'),
    ((SELECT id FROM inventory_transfer WHERE transfer_no='TR20260808001'), 1, '铜管',        '合格', '铜',     'Φ20mm',  200.0000,  '华东仓'),
    ((SELECT id FROM inventory_transfer WHERE transfer_no='TR20260808002'), 2, '电解铜 B级', 'B级',  '电解铜', '99.95%', 50.0000,   '华南仓');

-- 库存统计种子（material 与明细对齐，避免五维裂变；库龄 >= age_warn_days 时前端标红预警）
INSERT IGNORE INTO inventory_stock
    (product_name, grade, material, spec, org_id, actual_qty, transit_qty, stock_age, age_warn_days, first_inbound_at)
VALUES
    ('电解铜 A级', 'A级', '电解铜', '99.99%',  1, 3000.0000, 1000.0000, 20, 15, '2026-08-01 10:00:00'),
    ('铜管',        '合格', '铜',     'Φ20mm',   1,  900.0000,    0.0000,  5, 15, '2026-08-10 09:00:00'),
    ('电解铜 B级', 'B级', '电解铜', '99.95%',  2,  700.0000,  100.0000, 30, 15, '2026-07-20 10:00:00'),
    ('铜板 1.5mm', '合格', '铜',     '1.5mm',   1,  500.0000,    0.0000,  8, 15, '2026-08-06 10:00:00');

-- 批号种子
INSERT IGNORE INTO inventory_batch
    (batch_no, product_name, create_date, creator, remark)
VALUES
    ('BT20260801001', '电解铜 A级', '2026-08-01', '张三', '主力批号'),
    ('BT20260801002', '铜管',        '2026-08-01', '李四', '代销批号'),
    ('BT20260801003', '电解铜 B级', '2026-08-01', '王五', '内部批号');

-- 安全库存配置种子（该表当前无唯一键（P1-5 待补），INSERT IGNORE 不去重，故先删后插保证幂等）
DELETE FROM inventory_safe_stock
WHERE (product_name, material, org_id, service_level, z_value) IN
    (('电解铜 A级', '阴极铜板', 1, 95.00, 1.645),
     ('铜管 Φ20mm',  '紫铜管',   1, 98.00, 2.054),
     ('电解铜 B级', '阴极铜板', 2, 90.00, 1.282));
INSERT INTO inventory_safe_stock
    (product_name, material, org_id, service_level, z_value, replenish_cycle, economic_qty, order_point_qty, max_qty, safe_stock)
VALUES
    ('电解铜 A级', '阴极铜板', 1, 95.00, 1.645,  7, 1000.0000, 500.0000, 3000.0000, 200.0000),
    ('铜管 Φ20mm',  '紫铜管',   1, 98.00, 2.054,  5,  500.0000, 200.0000, 1500.0000, 100.0000),
    ('电解铜 B级', '阴极铜板', 2, 90.00, 1.282, 10,  800.0000, 400.0000, 2500.0000, 150.0000);

-- 操作日志种子（幂等：先删演示日志再插；operator=系统初始化 为种子标记）
DELETE FROM operation_log WHERE operator = '系统初始化';
INSERT INTO operation_log
    (module, operation, target_id, target_no, operator, detail)
VALUES
    ('inbound',  'CREATE', NULL, 'IN20260801001', '系统初始化', '{"items":2,"inboundType":"估价"}'),
    ('inbound',  'CHECK',  NULL, 'IN20260801001', '系统初始化', '{"checker":"张三","auditLevel":"总监审核"}'),
    ('outbound', 'CREATE', NULL, 'OUT20260805001', '系统初始化', '{"items":2,"freightBearer":"博宇承担"}');
