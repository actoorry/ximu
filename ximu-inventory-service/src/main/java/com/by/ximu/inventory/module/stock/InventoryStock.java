package com.by.ximu.inventory.module.stock;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 库存统计实体。
 *
 * <p>{@code warn} 与 {@code stockAgeDays} 为非表字段（@TableField(exist=false)），由 Controller 回填。
 */
@Data
@TableName("inventory_stock")
public class InventoryStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String productName;

    private String grade;

    /** 物料/材质（库存五维之一，缺省空串参与匹配） */
    private String material;

    private String spec;

    private Long orgId;

    private BigDecimal actualQty;

    /** 库龄预警阈值（天） */
    private Integer ageWarnDays;

    /** 首次入库时间（库龄计算用） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime firstInboundAt;

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

    /** 非表字段：库龄预警标记（stockAgeDays 非 null 时按 stockAgeDays >= ageWarnDays 判定；ageWarnDays 为 null 时恒为 false） */
    @TableField(exist = false)
    private Boolean warn;

    /** 非表字段：库龄天数（now - firstInboundAt 向下取整；firstInboundAt 为 null 时为 null） */
    @TableField(exist = false)
    private Long stockAgeDays;

    /**
     * 计算库龄天数：{@code now - firstInboundAt} 的整天数（向下取整）。
     *
     * @param firstInboundAt 首次入库时间，可为 null
     * @param now 参考时间点（通常传 {@link LocalDateTime#now()}，便于测试注入固定时间）
     * @return 库龄天数；firstInboundAt 为 null 时返回 null
     */
    public static Long stockAgeDays(LocalDateTime firstInboundAt, LocalDateTime now) {
        if (firstInboundAt == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(firstInboundAt, now);
    }

    /**
     * 库龄预警判定：仅动态值判定（V10 已删 stock_age 静态列，无静态回退）。
     *
     * <p>规则：{@code ageWarnDays} 为 null 时恒为 false（保持现状）；
     * {@code stockAgeDays} 非 null 时按动态值 {@code stockAgeDays >= ageWarnDays} 判定；
     * 动态值不可得（firstInboundAt 为 null）时为 false。
     *
     * @param stockAgeDays 动态库龄天数（now - firstInboundAt），可为 null
     * @param ageWarnDays 库龄预警阈值（天），可为 null
     * @return 是否触发库龄预警
     */
    public static boolean isWarn(Long stockAgeDays, Integer ageWarnDays) {
        if (ageWarnDays == null) {
            return false;
        }
        return stockAgeDays != null && stockAgeDays >= ageWarnDays;
    }
}
