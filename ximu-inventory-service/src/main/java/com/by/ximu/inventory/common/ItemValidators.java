package com.by.ximu.inventory.common;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单据明细/兼容单品的显式校验（R2-P1-1/2、P2-19）。
 *
 * <p>实体上的 {@code @Positive/@NotBlank} 等 Bean Validation 不拦截 null（仅非空对象参与校验），
 * 且兼容单品路径手工 {@code new} 明细会绕过级联校验；此处由 Service 在 {@code normalizeItems} 出口统一兜底，
 * 保证「明细至少一行、数量非空且合法」——防止 qty=null 经 {@code @Positive} 放行后在联动时静默跳过库存操作
 * （"白入库/白出库"）。
 */
public final class ItemValidators {

    private ItemValidators() {
    }

    /**
     * 明细至少一行：normalizeItems 已把兼容单品字段转成明细，此处 items 为空即「items 与兼容字段皆空」。
     *
     * @param items 归一化后的明细列表，可为 null
     * @param docType 单据类型（入库/出库/盘点/调拨），用于报错文案
     */
    public static void requireNonEmpty(List<?> items, String docType) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException(docType + "明细不能为空，至少需要一行商品");
        }
    }

    /** 数量必填且 &gt; 0：入库/出库/调拨的 qty 与入库结算数量 settleQty；为 0 或 null 会在联动时被静默跳过 */
    public static void requireQtyPositive(BigDecimal qty, String label) {
        if (qty == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException(label + "必须大于 0，当前值: " + qty);
        }
    }

    /** 数量非空且 &gt;= 0：盘点账面数量 bookQty（空库位账面为 0 属合法，仅拒绝 null 落库被 DEFAULT 0 篡改为假账面） */
    public static void requireQtyNotNullOrNegative(BigDecimal qty, String label) {
        if (qty == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (qty.signum() < 0) {
            throw new IllegalArgumentException(label + "不能为负，当前值: " + qty);
        }
    }

    /** 文本必填（非空白）：兼容单品路径的必填维度（品名/目标库位等），防构造出缺维度的明细 */
    public static void requireHasText(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("兼容单品字段不完整：" + label + " 必填");
        }
    }
}
