package com.by.ximu.safestock.module.safestock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /** 组织 ID（V8 起 org_id NOT NULL + 三维唯一键 uk_safe_stock_dims，创建时必填） */
    @NotNull(message = "组织(orgId)不能为空")
    private Long orgId;

    /** 幂等键：同一操作人重复提交相同 requestId 返回已有配置，不重复建（可空） */
    private String requestId;

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
