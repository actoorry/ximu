-- ============================================================
-- inventory-service schema：7 张表 + 种子数据
-- 幂等：CREATE TABLE IF NOT EXISTS + 先 DELETE 业务键再 INSERT
-- 每次启动（spring.sql.init.mode=always）可安全重复执行
-- ============================================================

-- 1. 入库管理
CREATE TABLE IF NOT EXISTS inventory_inbound (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inbound_no VARCHAR(64) NOT NULL COMMENT '入库单号',
    inbound_type VARCHAR(20) DEFAULT NULL COMMENT '估价/代销/内部',
    source_order_no VARCHAR(64) DEFAULT NULL COMMENT '来源单号',
    product_name VARCHAR(128) DEFAULT NULL,
    qty DECIMAL(18,4) DEFAULT NULL,
    settle_qty DECIMAL(18,4) DEFAULT NULL COMMENT '账面结算数量',
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED/CHECKED',
    checker VARCHAR(64) DEFAULT NULL,
    audit_level VARCHAR(20) DEFAULT NULL COMMENT '直接审核/总监审核/经理审核',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 出库管理
CREATE TABLE IF NOT EXISTS inventory_outbound (
    id BIGINT NOT NULL AUTO_INCREMENT,
    outbound_no VARCHAR(64) NOT NULL,
    sale_order_no VARCHAR(64) DEFAULT NULL,
    product_name VARCHAR(128) DEFAULT NULL,
    qty DECIMAL(18,4) DEFAULT NULL,
    freight_bearer VARCHAR(20) DEFAULT NULL COMMENT '博宇承担/对方承担',
    carrier VARCHAR(64) DEFAULT NULL,
    plate_no VARCHAR(7) DEFAULT NULL,
    driver VARCHAR(5) DEFAULT NULL,
    driver_phone VARCHAR(11) DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 盘点
CREATE TABLE IF NOT EXISTS inventory_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    check_no VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    actual_qty DECIMAL(18,4) DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED/CHECKED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 调拨
CREATE TABLE IF NOT EXISTS inventory_transfer (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transfer_no VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    qty DECIMAL(18,4) DEFAULT NULL,
    target_location VARCHAR(128) DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/APPROVED/COMPLETED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 库存统计
CREATE TABLE IF NOT EXISTS inventory_stock (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_name VARCHAR(128) DEFAULT NULL,
    grade VARCHAR(32) DEFAULT NULL,
    spec VARCHAR(64) DEFAULT NULL,
    org_id BIGINT DEFAULT NULL,
    actual_qty DECIMAL(18,4) DEFAULT NULL,
    transit_qty DECIMAL(18,4) DEFAULT NULL,
    stock_age INT DEFAULT 0 COMMENT '库龄（天）',
    age_warn_days INT DEFAULT 15 COMMENT '库龄预警阈值（天）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 批号管理
CREATE TABLE IF NOT EXISTS inventory_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_no VARCHAR(64) NOT NULL,
    product_name VARCHAR(128) DEFAULT NULL,
    create_date DATE DEFAULT NULL,
    creator VARCHAR(64) DEFAULT NULL,
    remark VARCHAR(512) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 操作日志
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    module VARCHAR(32) NOT NULL COMMENT '业务模块',
    operation VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/DELETE/APPROVE/CHECK',
    target_id BIGINT DEFAULT NULL,
    target_no VARCHAR(64) DEFAULT NULL,
    operator VARCHAR(64) DEFAULT NULL,
    detail TEXT DEFAULT NULL COMMENT 'JSON',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- 种子数据（真实业务：电解铜 A/B 级、铜管 Φ20mm 等）
-- ============================================================

-- 入库种子
DELETE FROM inventory_inbound WHERE inbound_no IN ('IN20260801001','IN20260801002','IN20260801003');
INSERT INTO inventory_inbound
    (inbound_no, inbound_type, source_order_no, product_name, qty, settle_qty, status, checker, audit_level)
VALUES
    ('IN20260801001', '估价', 'PO20260730001', '电解铜 A级', 5000.0000, 5000.0000, 'CHECKED',   '张三', '总监审核'),
    ('IN20260801002', '代销', 'PO20260730002', '铜管 Φ20mm',  1200.0000, 1200.0000, 'APPROVED',  NULL,   '经理审核'),
    ('IN20260801003', '内部', NULL,             '电解铜 B级',  800.0000,  NULL,       'CREATED',   NULL,   NULL);

-- 出库种子
DELETE FROM inventory_outbound WHERE outbound_no IN ('OUT20260805001','OUT20260805002','OUT20260805003');
INSERT INTO inventory_outbound
    (outbound_no, sale_order_no, product_name, qty, freight_bearer, carrier, plate_no, driver, driver_phone, status)
VALUES
    ('OUT20260805001', 'SO20260804001', '电解铜 A级', 2000.0000, '博宇承担', '顺丰物流', '京A12345', '王武', '13800138000', 'APPROVED'),
    ('OUT20260805002', 'SO20260804002', '铜管 Φ20mm',  300.0000, '对方承担', '德邦物流', '京B67890', '李四', '13900139000', 'CREATED'),
    ('OUT20260805003', 'SO20260804003', '电解铜 B级',  100.0000, '博宇承担', '自提',     '沪C00001', '赵六', '13700137000', 'CREATED');

-- 盘点种子
DELETE FROM inventory_check WHERE check_no IN ('CK20260810001','CK20260810002','CK20260810003');
INSERT INTO inventory_check
    (check_no, batch_no, actual_qty, status)
VALUES
    ('CK20260810001', 'BT20260801001', 4998.0000, 'CHECKED'),
    ('CK20260810002', 'BT20260801002', 1200.0000, 'APPROVED'),
    ('CK20260810003', 'BT20260801003',  800.0000, 'CREATED');

-- 调拨种子
DELETE FROM inventory_transfer WHERE transfer_no IN ('TR20260808001','TR20260808002','TR20260808003');
INSERT INTO inventory_transfer
    (transfer_no, batch_no, qty, target_location, status)
VALUES
    ('TR20260808001', 'BT20260801001', 1000.0000, '华东仓', 'COMPLETED'),
    ('TR20260808002', 'BT20260801002',  200.0000, '华南仓', 'APPROVED'),
    ('TR20260808003', 'BT20260801003',   50.0000, '华北仓', 'CREATED');

-- 库存统计种子（库龄 >= age_warn_days 时前端会标红预警）
DELETE FROM inventory_stock WHERE product_name IN ('电解铜 A级','铜管 Φ20mm','电解铜 B级','铜板 1.5mm');
INSERT INTO inventory_stock
    (product_name, grade, spec, org_id, actual_qty, transit_qty, stock_age, age_warn_days)
VALUES
    ('电解铜 A级', 'A级', '99.99% 纯度',  1, 3000.0000, 1000.0000, 20, 15),
    ('铜管 Φ20mm',  '合格', 'Φ20mm×3m',    1,  900.0000,    0.0000,  5, 15),
    ('电解铜 B级', 'B级', '99.95% 纯度',  2,  700.0000,  100.0000, 30, 15),
    ('铜板 1.5mm', '合格', '1.5mm×1m×2m', 1,  500.0000,    0.0000,  8, 15);

-- 批号种子
DELETE FROM inventory_batch WHERE batch_no IN ('BT20260801001','BT20260801002','BT20260801003');
INSERT INTO inventory_batch
    (batch_no, product_name, create_date, creator, remark)
VALUES
    ('BT20260801001', '电解铜 A级', '2026-08-01', '张三', '主力批号'),
    ('BT20260801002', '铜管 Φ20mm',  '2026-08-01', '李四', '代销批号'),
    ('BT20260801003', '电解铜 B级', '2026-08-01', '王五', '内部批号');

-- 操作日志种子（operator=系统初始化 作为种子标记，便于幂等清理）
DELETE FROM operation_log WHERE operator = '系统初始化';
INSERT INTO operation_log
    (module, operation, target_id, target_no, operator, detail)
VALUES
    ('inbound',  'CREATE', NULL, 'IN20260801001', '系统初始化', '{"qty":5000,"inboundType":"估价"}'),
    ('inbound',  'CHECK',  NULL, 'IN20260801001', '系统初始化', '{"checker":"张三","auditLevel":"总监审核"}'),
    ('outbound', 'CREATE', NULL, 'OUT20260805001', '系统初始化', '{"qty":2000,"freightBearer":"博宇承担"}');
