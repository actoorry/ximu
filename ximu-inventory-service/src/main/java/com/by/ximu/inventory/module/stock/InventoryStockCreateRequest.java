package com.by.ximu.inventory.module.stock;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 库存创建请求白名单 DTO。
 *
 * <p>与 update 白名单对齐：屏蔽 {@code id/version/createdAt/updatedAt/firstInboundAt} 及账本字段
 * （{@code actualQty/transitQty}，只能由单据流转产生）。
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

    /** 库龄（天）（可选，缺省 0） */
    private Integer stockAge;

    /** 库龄预警阈值（天）（可选，缺省 15） */
    private Integer ageWarnDays;
}
