package com.by.ximu.inventory.module.stock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库龄预警判定的纯单元测试（不依赖 Spring / 数据库）。
 *
 * <p>被测逻辑为 {@link InventoryStock#isWarn(Long, Integer)}，
 * 规则（V10 删 stock_age 静态列后仅动态判定）：stockAgeDays 非 null 时按
 * {@code stockAgeDays >= ageWarnDays} 判定；ageWarnDays 为 null 时恒为 false；
 * 动态值不可得（stockAgeDays 为 null）时为 false。
 */
class StockWarnTest {

    /** 动态库龄超阈值 → true */
    @Test
    void dynamicExceedsThresholdReturnsTrue() {
        assertTrue(InventoryStock.isWarn(10L, 5));
    }

    /** 动态库龄未超阈值 → false */
    @Test
    void dynamicBelowThresholdReturnsFalse() {
        assertFalse(InventoryStock.isWarn(3L, 5));
    }

    /** 动态库龄等于阈值（>= 边界）→ true */
    @Test
    void dynamicEqualsThresholdReturnsTrue() {
        assertTrue(InventoryStock.isWarn(5L, 5));
    }

    /** 动态值为 null（firstInboundAt 缺失）→ false，不再回退静态列（V10 已删） */
    @Test
    void dynamicNullReturnsFalse() {
        assertFalse(InventoryStock.isWarn(null, 5));
    }

    /** ageWarnDays 为 null → false（保持现状） */
    @Test
    void nullAgeWarnDaysReturnsFalse() {
        assertFalse(InventoryStock.isWarn(10L, null));
    }
}
