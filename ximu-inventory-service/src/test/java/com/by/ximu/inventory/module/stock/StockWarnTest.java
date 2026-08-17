package com.by.ximu.inventory.module.stock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库龄预警判定的纯单元测试（不依赖 Spring / 数据库）。
 *
 * <p>被测逻辑为 {@link InventoryStock#isWarn(Long, Integer, Integer)}，
 * 规则：动态值优先、静态值回退——stockAgeDays 非 null 时按 {@code stockAgeDays >= ageWarnDays} 判定；
 * 否则回退遗留静态列 {@code stockAge >= ageWarnDays}；ageWarnDays 为 null 时恒为 false。
 */
class StockWarnTest {

    /** 动态库龄超阈值 → true */
    @Test
    void dynamicExceedsThresholdReturnsTrue() {
        assertTrue(InventoryStock.isWarn(10L, 0, 5));
    }

    /** 动态库龄未超阈值 → false */
    @Test
    void dynamicBelowThresholdReturnsFalse() {
        assertFalse(InventoryStock.isWarn(3L, 0, 5));
    }

    /** 动态库龄等于阈值（>= 边界）→ true */
    @Test
    void dynamicEqualsThresholdReturnsTrue() {
        assertTrue(InventoryStock.isWarn(5L, 0, 5));
    }

    /** 动态值为 null 时回退静态列，静态超阈值 → true */
    @Test
    void dynamicNullFallsBackToStaticExceedsReturnsTrue() {
        assertTrue(InventoryStock.isWarn(null, 10, 5));
    }

    /** 动态值为 null 时回退静态列，静态未超阈值 → false */
    @Test
    void dynamicNullFallsBackToStaticBelowReturnsFalse() {
        assertFalse(InventoryStock.isWarn(null, 3, 5));
    }

    /** 动态值与静态列都为 null → false */
    @Test
    void bothNullReturnsFalse() {
        assertFalse(InventoryStock.isWarn(null, null, 5));
    }

    /** ageWarnDays 为 null → false（保持现状） */
    @Test
    void nullAgeWarnDaysReturnsFalse() {
        assertFalse(InventoryStock.isWarn(10L, 10, null));
    }
}
