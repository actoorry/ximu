package com.by.ximu.inventory.module.stock;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 库存创建请求白名单 DTO。
 *
 * <p>与 update 白名单对齐：屏蔽 {@code id/version/createdAt/updatedAt/firstInboundAt} 及账本字段
 * （{@code actualQty}，只能由单据流转产生）。
 * <p>创建仅建立「库存维度行」（orgId + 品名 + 材质 + 规格 + 等级），数量由后续单据流转产生。
 */
@Data
public class InventoryStockCreateRequest {

    @NotNull(message = "组织不能为空")
    private Long orgId;

    @NotBlank(message = "品名不能为空")
    private String productName;

    /** 等级（可选，缺省空串） */
    private String grade;

    /** 物料/材质（可选，缺省空串） */
    private String material;

    /** 规格（可选，缺省空串） */
    private String spec;

    /** 库龄预警阈值（天）（可选，缺省 15；R2-P2-18：0~365 天，负值/超大值在 DTO 层拦截，不再落库后才由 SQL 报错） */
    @Min(value = 0, message = "ageWarnDays 不能为负数")
    @Max(value = 365, message = "ageWarnDays 不能超过 365")
    private Integer ageWarnDays;
}
