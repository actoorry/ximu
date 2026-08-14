package com.by.jxc.inventory.module.inbound;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 入库单详情 VO：头字段（继承 {@link Inbound}） + 明细列表 + 数量汇总。
 *
 * <p>用于 GET /{id} 返回完整明细，GET 列表返回头 + totalQty。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InboundDetailVO extends Inbound {

    /** 明细列表 */
    private List<InboundItem> items;

    /** 明细数量汇总（便于列表展示） */
    private BigDecimal totalQty;
}
