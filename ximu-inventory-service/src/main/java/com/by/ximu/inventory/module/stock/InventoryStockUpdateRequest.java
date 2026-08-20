package com.by.ximu.inventory.module.stock;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 库存更新请求白名单 DTO（R2-P2-18）。
 *
 * <p>仅允许修改库龄预警阈值 ageWarnDays；禁止直接接收 {@code InventoryStock} 实体——
 * 原实现 PUT 全量覆盖且暴露账本字段（actualQty/firstInboundAt）可被前端覆盖的风险面。
 * 部分更新语义：ageWarnDays 为 null 表示保持原值。
 */
@Data
public class InventoryStockUpdateRequest {

    /** 库龄预警阈值（天）（可选；0~365 天，负值/超大值 DTO 层拦截） */
    @Min(value = 0, message = "ageWarnDays 不能为负数")
    @Max(value = 365, message = "ageWarnDays 不能超过 365")
    private Integer ageWarnDays;
}
