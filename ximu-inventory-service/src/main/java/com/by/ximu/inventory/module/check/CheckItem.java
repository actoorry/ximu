package com.by.ximu.inventory.module.check;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 盘点明细实体（表名 check_item，头表 inventory_check 的多行商品明细）。
 */
@Data
@TableName("check_item")
public class CheckItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 盘点单头ID */
    private Long checkId;

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

    /** 账面数量 */
    @PositiveOrZero(message = "账面数量不能为负")
    private BigDecimal bookQty;

    /** 实盘数量 */
    @PositiveOrZero(message = "实盘数量不能为负")
    private BigDecimal actualQty;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
