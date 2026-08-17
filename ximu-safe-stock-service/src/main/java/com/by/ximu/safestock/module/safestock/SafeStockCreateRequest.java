package com.by.ximu.safestock.module.safestock;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 安全库存配置创建请求白名单 DTO。
 *
 * <p>与 update 白名单对齐：屏蔽 {@code id/version/createdAt/updatedAt}。
 */
@Data
public class SafeStockCreateRequest {

    @NotBlank(message = "品名不能为空")
    private String productName;

    private String material;

    private Long orgId;

    /** 有货率（%） */
    private BigDecimal serviceLevel;

    /** Z 值 */
    private BigDecimal zValue;

    /** 补货周期（天） */
    private Integer replenishCycle;

    /** 经济订货量 */
    private BigDecimal economicQty;

    /** 订货点 */
    private BigDecimal orderPointQty;

    /** 最高库存 */
    private BigDecimal maxQty;

    /** 安全库存 */
    private BigDecimal safeStock;
}
