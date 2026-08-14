package com.by.jxc.inventory.module.transfer;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 调拨单详情 VO：头字段（继承 {@link Transfer}） + 明细列表 + 数量汇总。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TransferDetailVO extends Transfer {

    /** 明细列表 */
    private List<TransferItem> items;

    /** 明细数量汇总（便于列表展示） */
    private BigDecimal totalQty;
}
