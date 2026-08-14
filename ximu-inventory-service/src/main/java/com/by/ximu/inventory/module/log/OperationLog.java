package com.by.ximu.inventory.module.log;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务模块：inbound/outbound/check/transfer/stock/batch */
    private String module;

    /** 操作类型：CREATE/UPDATE/DELETE/APPROVE/CHECK/COMPLETE */
    private String operation;

    /** 目标单据 ID */
    private Long targetId;

    /** 目标单据编号 */
    private String targetNo;

    /** 操作人 */
    private String operator;

    /** 操作详情（JSON 文本） */
    private String detail;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
