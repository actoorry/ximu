package com.by.ximu.inventory.module.outbound;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 出库单详情 VO：头字段（继承 {@link Outbound}） + 明细列表 + 数量汇总。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OutboundDetailVO extends Outbound {

    /** 明细列表 */
    private List<OutboundItem> items;

    /** 明细数量汇总（便于列表展示） */
    private BigDecimal totalQty;
}
