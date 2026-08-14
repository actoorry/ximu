package com.by.jxc.inventory.module.inbound;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入库管理实体。
 *
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

    private String productName;

    private BigDecimal qty;

    /** 账面结算数量 */
    private BigDecimal settleQty;

    /** CREATED / APPROVED / CHECKED */
    private String status;

    private String checker;

    /** 直接审核 / 总监审核 / 经理审核 */
    private String auditLevel;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
