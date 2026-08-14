package com.by.ximu.inventory.module.check;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 盘点单详情 VO：头字段（继承 {@link InventoryCheck}） + 明细列表 + 实盘数量汇总。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CheckDetailVO extends InventoryCheck {

    /** 明细列表 */
    private List<CheckItem> items;

    /** 明细实盘数量汇总（便于列表展示） */
    private BigDecimal totalQty;
}
