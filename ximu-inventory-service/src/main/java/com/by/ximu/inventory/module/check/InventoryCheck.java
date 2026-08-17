package com.by.ximu.inventory.module.check;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点实体（表名 inventory_check，类名避开 Java 通用词 check；单据头）。
 *
 * <p>商品行已下沉到 {@link CheckItem} 明细表，一张盘点单可含多行明细。
 * <p>状态机：CREATED → APPROVED → CHECKED
 */
@Data
@TableName("inventory_check")
public class InventoryCheck {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message = "盘点单号不能为空")
    private String checkNo;

    private String batchNo;

    /** CREATED / APPROVED / CHECKED */
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
