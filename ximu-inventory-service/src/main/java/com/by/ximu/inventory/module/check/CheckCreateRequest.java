package com.by.ximu.inventory.module.check;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 盘点单创建请求（头字段 + 明细列表）。
 *
 * <p>明细承载每行商品的 {@code bookQty（账面数量）} 与 {@code actualQty（实盘数量）}。
 * <p>{@code checkNo} 可选：不传则后端自动生成（{@code CK + yyyyMMdd + 3位序号}）。
 * <p>兼容旧版单品字段：{@code items} 为空但传了 {@code actualQty} 时自动转成一条明细。
 */
@Data
public class CheckCreateRequest {

    /** 单号（可选，不传则后端自动生成） */
    /** 客户端幂等键（防双击/重试重复建单，可选） */
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    @Size(max = 64, message = "单号长度不能超过 64")
    private String checkNo;

    @Size(max = 64, message = "批号长度不能超过 64")
    private String batchNo;

    /** 明细列表（推荐传多行商品） */
    @Valid
    @Size(max = 200, message = "明细行数不能超过 200")
    private List<CheckItem> items;

    // ===== 兼容旧版单品字段（旧盘点头仅有 actualQty，productName/spec 可选补传） =====
    private Long orgId;
    private String productName;
    private String grade;
    private String spec;
    @PositiveOrZero(message = "实盘数量不能为负")
    private BigDecimal actualQty;
}
