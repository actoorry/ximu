package com.by.jxc.inventory.module.check;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 盘点实体（表名 inventory_check，类名避开 Java 通用词 check）。
 *
 * <p>状态机：CREATED → APPROVED → CHECKED
 */
@Data
@TableName("inventory_check")
public class InventoryCheck {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String checkNo;

    private String batchNo;

    private BigDecimal actualQty;

    /** CREATED / APPROVED / CHECKED */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}
