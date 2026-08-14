package com.by.jxc.inventory.module.outbound;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库明细实体（头表 inventory_outbound 的多行商品明细，一张出库单可含多行）。
 */
@Data
@TableName("outbound_item")
public class OutboundItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 出库单头ID */
    private Long outboundId;

    /** 品名 */
    private String productName;

    /** 物料/材质 */
    private String material;

    /** 规格 */
    private String spec;

    /** 数量 */
    private BigDecimal qty;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
