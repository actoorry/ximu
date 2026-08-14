package com.by.jxc.inventory.module.stock;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存统计实体。
 *
 * <p>{@code warn} 为非表字段（@TableField(exist=false)），由 Controller 根据库龄计算后回填。
 */
@Data
@TableName("inventory_stock")
public class InventoryStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String productName;

    private String grade;

    private String spec;

    private Long orgId;

    private BigDecimal actualQty;

    private BigDecimal transitQty;

    /** 库龄（天） */
    private Integer stockAge;

    /** 库龄预警阈值（天） */
    private Integer ageWarnDays;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 非表字段：库龄预警标记（stockAge >= ageWarnDays 时为 true） */
    @TableField(exist = false)
    private Boolean warn;
}
