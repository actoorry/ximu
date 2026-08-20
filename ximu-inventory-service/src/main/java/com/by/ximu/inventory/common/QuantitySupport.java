package com.by.ximu.inventory.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * 单据明细数量汇总支撑：头表分页/详情里 {@code totalQty}（明细数量之和）的统一计算。
 *
 * <p>原 Inbound/Outbound/Transfer 的 {@code sumQty()} 与 InventoryCheck 的 {@code sumActualQty()}
 * 四处重复（仅取数 getter 不同），统一收拢为泛型汇总，调用方传入数量字段引用即可。
 */
public final class QuantitySupport {

    private QuantitySupport() {
    }

    /**
     * 汇总明细数量：null 明细或空列表返回 {@link BigDecimal#ZERO}；单行数量为 null 按 0 计。
     *
     * @param items     明细列表，可为 null
     * @param qtyGetter 数量字段引用（如 {@code InboundItem::getQty} / {@code CheckItem::getActualQty}）
     */
    public static <T> BigDecimal sumQty(List<T> items, Function<T, BigDecimal> qtyGetter) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(qtyGetter)
                .map(q -> q == null ? BigDecimal.ZERO : q)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
