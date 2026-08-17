package com.by.ximu.inventory.module.transfer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调拨明细实体（头表 inventory_transfer 的多行商品明细，一张调拨单可含多行）。
 */
@Data
@TableName("transfer_item")
public class TransferItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调拨单头ID */
    private Long transferId;

    /** 组织ID（必填） */
    @NotNull(message = "组织不能为空")
    private Long orgId;

    /** 品名 */
    @NotBlank(message = "品名不能为空")
    private String productName;

    /** 等级（可选，联动库存时缺省按空串匹配） */
    private String grade;

    /** 物料/材质 */
    private String material;

    /** 规格 */
    private String spec;

    /** 数量 */
    @Positive(message = "数量必须为正数")
    private BigDecimal qty;

    /** 目标库位 */
    private String targetLocation;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
