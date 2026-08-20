package com.by.ximu.inventory.module.inbound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
    /** 客户端幂等键（防双击/重试重复建单，可选） */
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    @Size(max = 64, message = "单号长度不能超过 64")
    private String inboundNo;

    /** 估价 / 代销 / 内部 */
    @Size(max = 20, message = "入库类型长度不能超过 20")
    private String inboundType;

    @Size(max = 64, message = "来源单号长度不能超过 64")
    private String sourceOrderNo;

    @Size(max = 64, message = "验收人长度不能超过 64")
    private String checker;

    /** 直接审核 / 总监审核 / 经理审核 */
    @Size(max = 20, message = "审核级别长度不能超过 20")
    private String auditLevel;

    /** 明细列表（推荐传多行商品） */
    @Valid
    @Size(max = 200, message = "明细行数不能超过 200")
    private List<InboundItem> items;

    // ===== 兼容旧版单品字段（头里的单行商品），items 为空时自动转成一条明细 =====
    private Long orgId;
    private String productName;
    private String grade;
    @Positive(message = "数量必须为正数")
    private BigDecimal qty;
    @PositiveOrZero(message = "账面结算数量不能为负")
    private BigDecimal settleQty;
}
