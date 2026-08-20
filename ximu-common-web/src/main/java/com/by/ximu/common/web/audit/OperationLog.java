package com.by.ximu.common.web.audit;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志实体（inventory 与 safe-stock 两微服务共享同一张 operation_log 表，同库 ximu）。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务模块：inbound/outbound/check/transfer/stock/batch/safe-stock 等 */
    private String module;

    /** 操作类型：CREATE/UPDATE/DELETE/APPROVE/CHECK/COMPLETE */
    private String operation;

    /** 目标单据 ID */
    private Long targetId;

    /** 目标单据编号/标识（安全库存无独立单号，以商品名近似） */
    private String targetNo;

    /** 操作人 */
    private String operator;

    /** 操作人用户ID（P2-5：姓名可重名/可改名，ID 才是唯一审计锚点；由 MetaObjectHandler 自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private Long operatorId;

    /** 操作详情（JSON 文本） */
    private String detail;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;
}
