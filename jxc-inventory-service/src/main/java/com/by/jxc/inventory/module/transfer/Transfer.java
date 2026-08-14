package com.by.jxc.inventory.module.transfer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调拨实体。
 *
 * <p>状态机：CREATED → APPROVED → COMPLETED
 */
@Data
@TableName("inventory_transfer")
public class Transfer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String transferNo;

    private String batchNo;

    private BigDecimal qty;

    private String targetLocation;

    /** CREATED / APPROVED / COMPLETED */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}
