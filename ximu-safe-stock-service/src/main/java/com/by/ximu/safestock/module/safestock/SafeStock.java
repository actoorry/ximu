package com.by.ximu.safestock.module.safestock;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 安全库存配置实体。
 */
@Data
@TableName("inventory_safe_stock")
public class SafeStock {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
