package com.by.ximu.inventory.module.outbound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
    /** 客户端幂等键（防双击/重试重复建单，可选） */
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    @Size(max = 64, message = "单号长度不能超过 64")
    private String outboundNo;

    @Size(max = 64, message = "销售单号长度不能超过 64")
    private String saleOrderNo;

    /** 博宇承担 / 对方承担 */
    @Size(max = 20, message = "运费承担方长度不能超过 20")
    private String freightBearer;

    @Size(max = 64, message = "承运商长度不能超过 64")
    private String carrier;

    @Size(max = 7, message = "车牌号长度不能超过 7")
    private String plateNo;

    @Size(max = 5, message = "司机姓名长度不能超过 5")
    private String driver;

    @Size(max = 32, message = "司机电话长度不能超过 32")
    private String driverPhone;

    /** 明细列表（推荐传多行商品） */
    @Valid
    @Size(max = 200, message = "明细行数不能超过 200")
    private List<OutboundItem> items;

    // ===== 兼容旧版单品字段 =====
    private Long orgId;
    private String productName;
    private String grade;
    @Positive(message = "数量必须为正数")
    private BigDecimal qty;
}
