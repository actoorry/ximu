package com.by.ximu.inventory.module.inbound;

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
 * 入库管理实体（单据头）。
 *
 * <p>商品行已下沉到 {@link InboundItem} 明细表，一张入库单可含多行明细。
 * <p>状态机：CREATED → APPROVED → CHECKED
 */
@Data
@TableName("inventory_inbound")
public class Inbound {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "入库单号不能为空")
    private String inboundNo;

    /** 估价 / 代销 / 内部 */
    private String inboundType;

    private String sourceOrderNo;

    /** CREATED / APPROVED / CHECKED */
    private String status;

    /** 制单人ID（用于职责分离校验） */
    private Long createdBy;

    /** 客户端幂等键（防双击/重试重复建单） */
    private String requestId;

    private String checker;

    /** 直接审核 / 总监审核 / 经理审核 */
    private String auditLevel;

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
