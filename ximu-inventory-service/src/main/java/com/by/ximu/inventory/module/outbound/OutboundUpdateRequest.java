package com.by.ximu.inventory.module.outbound;

import lombok.Data;

/**
 * 出库单编辑请求（白名单 DTO，防过度绑定）。
 *
 * <p>仅暴露业务可编辑字段；{@code id/status/version/createdBy/createdAt/updatedAt}
 * 不在此声明，即使前端传入也会被忽略。
 * <p>部分更新语义：字段为 null 表示保持原值不变。
 */
@Data
public class OutboundUpdateRequest {

    private String saleOrderNo;

    /** 运费承担方 */
    private String freightBearer;

    /** 承运商 */
    private String carrier;

    /** 车牌号 */
    private String plateNo;

    /** 司机 */
    private String driver;

    /** 司机电话 */
    private String driverPhone;
}
