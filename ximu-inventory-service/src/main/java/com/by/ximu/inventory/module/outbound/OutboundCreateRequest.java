package com.by.ximu.inventory.module.outbound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 出库单创建请求（头字段 + 明细列表）。
 *
 * <p>{@code outboundNo} 可选：不传则后端自动生成（{@code OUT + yyyyMMdd + 3位序号}）。
 * <p>兼容旧版单品字段：{@code items} 为空但传了 {@code productName/qty} 时自动转成一条明细。
 */
@Data
public class OutboundCreateRequest {

    /** 单号（可选，不传则后端自动生成） */
    private String outboundNo;

    private String saleOrderNo;

    /** 博宇承担 / 对方承担 */
    private String freightBearer;

    private String carrier;

    private String plateNo;

    private String driver;

    private String driverPhone;

    /** 明细列表（推荐传多行商品） */
    @Valid
    private List<OutboundItem> items;

    // ===== 兼容旧版单品字段 =====
    private Long orgId;
    private String productName;
    private String grade;
    @Positive(message = "数量必须为正数")
    private BigDecimal qty;
}
