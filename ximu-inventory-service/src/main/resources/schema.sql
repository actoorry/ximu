-- ============================================================
-- inventory-service schema：7 张头/基础表 + 4 张明细表 + 种子数据（DDL 参考文档）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE INTO（配合业务键唯一索引）；
--       明细表无业务唯一键，种子用「先 DELETE 关联头明细，再 INSERT」实现幂等。
-- 注意：spring.sql.init.mode 默认 never，本文件不会自动执行；
--       开发环境可临时改为 always 自动建表+种子，生产环境必须用 Flyway/Liquibase 或手动管理 DDL。
-- ============================================================

-- 1. 入库管理（头表：保留单号/类型/状态/审核等头级字段，商品行下沉到明细表）
CREATE TABLE IF NOT EXISTS inventory_inbound (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inbound_no VARCHAR(64) NOT NULL COMMENT '入库单号',
    inbound_type VARCHAR(20) DEFAULT NULL COMMENT '估价/代销/内部',
    source_order_no VARCHAR(64) DEFAULT NULL COMMENT '来源单号',
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED/CHECKED',
    created_by BIGINT NOT NULL COMMENT '制单人ID',
    checker VARCHAR(64) DEFAULT NULL,
    audit_level VARCHAR(20) DEFAULT NULL COMMENT '直接审核/总监审核/经理审核',
    request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_no (inbound_no),
    UNIQUE KEY uk_inbound_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 1.1 入库明细（一张入库单可含多行商品）
CREATE TABLE IF NOT EXISTS inbound_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inbound_id BIGINT NOT NULL COMMENT '入库单头ID',
    org_id BIGINT NOT NULL COMMENT '组织ID',
    product_name VARCHAR(128) DEFAULT NULL COMMENT '品名',
    grade VARCHAR(32) DEFAULT NULL COMMENT '等级（缺省空串参与库存匹配）',
    material VARCHAR(128) DEFAULT NULL COMMENT '物料/材质',
    spec VARCHAR(64) DEFAULT NULL COMMENT '规格',
    qty DECIMAL(18,4) DEFAULT NULL COMMENT '数量',
    settle_qty DECIMAL(18,4) DEFAULT NULL COMMENT '账面结算数量',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_inbound_item_doc (inbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 出库管理（头表）
CREATE TABLE IF NOT EXISTS inventory_outbound (
    id BIGINT NOT NULL AUTO_INCREMENT,
    outbound_no VARCHAR(64) NOT NULL,
    sale_order_no VARCHAR(64) DEFAULT NULL,
    freight_bearer VARCHAR(20) DEFAULT NULL COMMENT '博宇承担/对方承担',
    carrier VARCHAR(64) DEFAULT NULL,
    plate_no VARCHAR(7) DEFAULT NULL,
    driver VARCHAR(5) DEFAULT NULL,
    driver_phone VARCHAR(11) DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED',
    created_by BIGINT NOT NULL COMMENT '制单人ID',
    request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbound_no (outbound_no),
    UNIQUE KEY uk_outbound_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2.1 出库明细
CREATE TABLE IF NOT EXISTS outbound_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    outbound_id BIGINT NOT NULL COMMENT '出库单头ID',
    org_id BIGINT NOT NULL COMMENT '组织ID',
    product_name VARCHAR(128) DEFAULT NULL,
    grade VARCHAR(32) DEFAULT NULL COMMENT '等级（缺省空串参与库存匹配）',
    material VARCHAR(128) DEFAULT NULL,
    spec VARCHAR(64) DEFAULT NULL,
    qty DECIMAL(18,4) DEFAULT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_outbound_item_doc (outbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 盘点（头表）
CREATE TABLE IF NOT EXISTS inventory_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    check_no VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键',
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED/CHECKED',
    created_by BIGINT NOT NULL COMMENT '制单人ID',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_check_no (check_no),
    UNIQUE KEY uk_check_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.1 盘点明细
CREATE TABLE IF NOT EXISTS check_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    check_id BIGINT NOT NULL COMMENT '盘点单头ID',
    org_id BIGINT NOT NULL COMMENT '组织ID',
    product_name VARCHAR(128) DEFAULT NULL,
    grade VARCHAR(32) DEFAULT NULL COMMENT '等级（缺省空串参与库存匹配）',
    material VARCHAR(128) DEFAULT NULL,
    spec VARCHAR(64) DEFAULT NULL,
    book_qty DECIMAL(18,4) DEFAULT NULL COMMENT '账面数量',
    actual_qty DECIMAL(18,4) DEFAULT NULL COMMENT '实盘数量',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_check_item_doc (check_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 调拨（头表）
CREATE TABLE IF NOT EXISTS inventory_transfer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transfer_no VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    request_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等键',
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED/COMPLETED',
    created_by BIGINT NOT NULL COMMENT '制单人ID',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transfer_no (transfer_no),
    UNIQUE KEY uk_transfer_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4.1 调拨明细
CREATE TABLE IF NOT EXISTS transfer_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transfer_id BIGINT NOT NULL COMMENT '调拨单头ID',
    org_id BIGINT NOT NULL COMMENT '组织ID',
    product_name VARCHAR(128) DEFAULT NULL,
    grade VARCHAR(32) DEFAULT NULL COMMENT '等级（缺省空串参与库存匹配）',
    material VARCHAR(128) DEFAULT NULL,
    spec VARCHAR(64) DEFAULT NULL,
    qty DECIMAL(18,4) DEFAULT NULL,
    target_location VARCHAR(128) DEFAULT NULL COMMENT '目标库位',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_transfer_item_doc (transfer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 库存统计（出入库/盘点终态联动此表：actual_qty 增减/校正）
CREATE TABLE IF NOT EXISTS inventory_stock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_name VARCHAR(128) NOT NULL,
    grade VARCHAR(32) NOT NULL DEFAULT '',
    spec VARCHAR(64) NOT NULL DEFAULT '',
    org_id BIGINT NOT NULL,
    actual_qty DECIMAL(18,4) DEFAULT NULL,
    transit_qty DECIMAL(18,4) DEFAULT NULL,
    stock_age INT DEFAULT 0 COMMENT '库龄（天）',
    age_warn_days INT DEFAULT 15 COMMENT '库龄预警阈值（天）',
    first_inbound_at DATETIME NULL COMMENT '首次入库时间（库龄计算用）',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_dims (org_id, product_name, spec, grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 批号管理
CREATE TABLE IF NOT EXISTS inventory_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(64) NOT NULL,
    product_name VARCHAR(128) DEFAULT NULL,
    create_date DATE DEFAULT NULL,
    creator VARCHAR(64) DEFAULT NULL,
    remark VARCHAR(512) DEFAULT NULL,
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 操作日志（无业务键唯一约束，仅追加，不建唯一键）
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    module VARCHAR(32) NOT NULL COMMENT '业务模块',
    operation VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/DELETE/APPROVE/CHECK',
    target_id BIGINT DEFAULT NULL,
    target_no VARCHAR(64) DEFAULT NULL,
    operator VARCHAR(64) DEFAULT NULL,
    detail TEXT DEFAULT NULL COMMENT 'JSON',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 种子数据（真实业务：电解铜 A/B 级、铜管 Φ20mm 等）
-- 头表用 INSERT IGNORE INTO（单号唯一键兜底，遇重复自动跳过，不误删真实数据）；
-- 明细表无业务唯一键，用「先 DELETE 关联头明细，再 INSERT」实现幂等。
-- 注意：operation_log 无业务键唯一索引，重复执行会追加；inventory_stock 已按
--       (org_id, product_name, spec, grade) 建唯一索引，INSERT IGNORE 重复执行幂等。
--       仅适合在 mode=never 场景手动执行作为示例数据。
-- ============================================================

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

-- 库存统计种子（库龄 >= age_warn_days 时前端会标红预警）
INSERT IGNORE INTO inventory_stock
    (product_name, grade, spec, org_id, actual_qty, transit_qty, stock_age, age_warn_days, first_inbound_at)
VALUES
    ('电解铜 A级', 'A级', '99.99%',  1, 3000.0000, 1000.0000, 20, 15, '2026-08-01 10:00:00'),
    ('铜管',        '合格', 'Φ20mm',   1,  900.0000,    0.0000,  5, 15, '2026-08-10 09:00:00'),
    ('电解铜 B级', 'B级', '99.95%',  2,  700.0000,  100.0000, 30, 15, '2026-07-20 10:00:00'),
    ('铜板 1.5mm', '合格', '1.5mm',   1,  500.0000,    0.0000,  8, 15, '2026-08-06 10:00:00');

-- 批号种子
INSERT IGNORE INTO inventory_batch
    (batch_no, product_name, create_date, creator, remark)
VALUES
    ('BT20260801001', '电解铜 A级', '2026-08-01', '张三', '主力批号'),
    ('BT20260801002', '铜管',        '2026-08-01', '李四', '代销批号'),
    ('BT20260801003', '电解铜 B级', '2026-08-01', '王五', '内部批号');

-- 操作日志种子（operator=系统初始化 作为种子标记）
INSERT IGNORE INTO operation_log
    (module, operation, target_id, target_no, operator, detail)
VALUES
    ('inbound',  'CREATE', NULL, 'IN20260801001', '系统初始化', '{"items":2,"inboundType":"估价"}'),
    ('inbound',  'CHECK',  NULL, 'IN20260801001', '系统初始化', '{"checker":"张三","auditLevel":"总监审核"}'),
    ('outbound', 'CREATE', NULL, 'OUT20260805001', '系统初始化', '{"items":2,"freightBearer":"博宇承担"}');

-- 单号序列表（原子取号）：seq_key = 单据前缀 + yyyyMMdd，多实例下通过 DB 原子自增保证当天同类单据序号不重复
CREATE TABLE IF NOT EXISTS doc_no_seq (seq_key VARCHAR(64) NOT NULL PRIMARY KEY, seq BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '单号序列（原子取号）';

-- 安全库存配置表（safe-stock-service 依赖；迁移权归 inventory-service，见 V3 迁移）
CREATE TABLE IF NOT EXISTS inventory_safe_stock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_name VARCHAR(128) DEFAULT NULL,
    material VARCHAR(128) DEFAULT NULL,
    org_id BIGINT DEFAULT NULL,
    service_level DECIMAL(5,2) DEFAULT NULL COMMENT '有货率（%）',
    z_value DECIMAL(6,3) DEFAULT NULL COMMENT 'Z值',
    replenish_cycle INT DEFAULT NULL COMMENT '补货周期（天）',
    economic_qty DECIMAL(18,4) DEFAULT NULL,
    order_point_qty DECIMAL(18,4) DEFAULT NULL,
    max_qty DECIMAL(18,4) DEFAULT NULL,
    safe_stock DECIMAL(18,4) DEFAULT NULL,
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 安全库存种子
INSERT IGNORE INTO inventory_safe_stock
    (product_name, material, org_id, service_level, z_value, replenish_cycle, economic_qty, order_point_qty, max_qty, safe_stock)
VALUES
    ('电解铜 A级', '阴极铜板', 1, 95.00, 1.645,  7, 1000.0000, 500.0000, 3000.0000, 200.0000),
    ('铜管 Φ20mm',  '紫铜管',   1, 98.00, 2.054,  5,  500.0000, 200.0000, 1500.0000, 100.0000),
    ('电解铜 B级', '阴极铜板', 2, 90.00, 1.282, 10,  800.0000, 400.0000, 2500.0000, 150.0000);

