package com.by.ximu.inventory.module.outbound;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出库管理实体（单据头）。
 *
 * <p>商品行已下沉到 {@link OutboundItem} 明细表，一张出库单可含多行明细。
 * <p>状态机：CREATED → APPROVED
 */
@Data
@TableName("inventory_outbound")
public class Outbound {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "出库单号不能为空")
    private String outboundNo;

    private String saleOrderNo;

    /** 博宇承担 / 对方承担 */
    private String freightBearer;

    private String carrier;

    private String plateNo;

    private String driver;

    private String driverPhone;

    /** CREATED / APPROVED */
    private String status;

    /** 制单人ID（用于职责分离校验） */
    private Long createdBy;

    /** 客户端幂等键（防双击/重试重复建单） */
    private String requestId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 最后修改人ID（P2-5，MetaObjectHandler 从登录上下文自动填充） */
    @TableField(fill = FieldFill.UPDATE)
    private Long updatedBy;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
