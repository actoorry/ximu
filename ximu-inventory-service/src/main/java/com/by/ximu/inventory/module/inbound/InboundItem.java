package com.by.ximu.inventory.module.inbound;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入库明细实体（头表 inventory_inbound 的多行商品明细，一张入库单可含多行）。
 */
@Data
@TableName("inbound_item")
public class InboundItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 入库单头ID */
    private Long inboundId;

    /** 品名 */
    private String productName;

    /** 物料/材质 */
    private String material;

    /** 规格 */
    private String spec;

    /** 数量 */
    private BigDecimal qty;

    /** 账面结算数量 */
    private BigDecimal settleQty;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
