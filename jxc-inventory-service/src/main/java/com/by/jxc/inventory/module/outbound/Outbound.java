package com.by.jxc.inventory.module.outbound;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库管理实体。
 *
 * <p>状态机：CREATED → APPROVED
 */
@Data
@TableName("inventory_outbound")
public class Outbound {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String outboundNo;

    private String saleOrderNo;

    private String productName;

    private BigDecimal qty;

    /** 博宇承担 / 对方承担 */
    private String freightBearer;

    private String carrier;

    private String plateNo;

    private String driver;

    private String driverPhone;

    /** CREATED / APPROVED */
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}
