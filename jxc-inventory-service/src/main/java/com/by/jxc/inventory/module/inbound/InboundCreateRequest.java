package com.by.jxc.inventory.module.inbound;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 入库单创建请求（头字段 + 明细列表）。
 *
 * <p>{@code inboundNo} 可选：不传则后端自动生成（{@code IN + yyyyMMdd + 3位序号}）；
 * 传了则校验唯一。
 * <p>兼容旧版单品字段：当 {@code items} 为空但传了 {@code productName/qty} 时，
 * 自动转成一条明细，避免前端联调断裂。
 */
@Data
public class InboundCreateRequest {

    /** 单号（可选，不传则后端自动生成） */
    private String inboundNo;

    /** 估价 / 代销 / 内部 */
    private String inboundType;

    private String sourceOrderNo;

    private String checker;

    /** 直接审核 / 总监审核 / 经理审核 */
    private String auditLevel;

    /** 明细列表（推荐传多行商品） */
    private List<InboundItem> items;

    // ===== 兼容旧版单品字段（头里的单行商品），items 为空时自动转成一条明细 =====
    private String productName;
    private BigDecimal qty;
    private BigDecimal settleQty;
}
