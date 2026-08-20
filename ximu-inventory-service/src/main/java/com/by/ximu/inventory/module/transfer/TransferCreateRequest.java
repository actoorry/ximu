package com.by.ximu.inventory.module.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 调拨单创建请求（头字段 + 明细列表）。
 *
 * <p>目标库位等行级信息下沉到明细 {@link TransferItem#targetLocation}。
 * <p>{@code transferNo} 可选：不传则后端自动生成（{@code TR + yyyyMMdd + 3位序号}）。
 * <p>兼容旧版单品字段：{@code items} 为空但传了 {@code productName/qty} 时自动转成一条明细。
 */
@Data
public class TransferCreateRequest {

    /** 单号（可选，不传则后端自动生成） */
    /** 客户端幂等键（防双击/重试重复建单，可选） */
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    @Size(max = 64, message = "单号长度不能超过 64")
    private String transferNo;

    @Size(max = 64, message = "批号长度不能超过 64")
    private String batchNo;

    /** 明细列表（推荐传多行商品） */
    @Valid
    @Size(max = 200, message = "明细行数不能超过 200")
    private List<TransferItem> items;

    // ===== 兼容旧版单品字段 =====
    private Long orgId;
    private String productName;
    private String grade;
    @Positive(message = "数量必须为正数")
    private BigDecimal qty;
    private String targetLocation;
}
