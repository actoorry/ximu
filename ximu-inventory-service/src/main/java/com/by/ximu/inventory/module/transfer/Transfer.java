package com.by.ximu.inventory.module.transfer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调拨实体（单据头）。
 *
 * <p>商品行已下沉到 {@link TransferItem} 明细表，一张调拨单可含多行明细。
 * <p>状态机：CREATED → APPROVED → COMPLETED
 */
@Data
@TableName("inventory_transfer")
public class Transfer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "调拨单号不能为空")
    private String transferNo;

    private String batchNo;

    /** CREATED / APPROVED / COMPLETED */
    private String status;

    /** 制单人ID（用于职责分离校验） */
    private Long createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
