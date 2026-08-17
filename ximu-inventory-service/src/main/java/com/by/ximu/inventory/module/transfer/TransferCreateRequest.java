package com.by.ximu.inventory.module.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
    private String transferNo;

    private String batchNo;

    /** 明细列表（推荐传多行商品） */
    @Valid
    private List<TransferItem> items;

    // ===== 兼容旧版单品字段 =====
    private Long orgId;
    private String productName;
    private String grade;
    @Positive(message = "数量必须为正数")
    private BigDecimal qty;
    private String targetLocation;
}
