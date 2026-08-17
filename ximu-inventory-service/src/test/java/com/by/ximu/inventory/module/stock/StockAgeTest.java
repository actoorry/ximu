package com.by.ximu.inventory.module.stock;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 库龄天数计算的纯单元测试（不依赖 Spring / 数据库）。
 *
 * <p>被测逻辑为 {@link InventoryStock#stockAgeDays(LocalDateTime, LocalDateTime)}，
 * 规则：{@code now - firstInboundAt} 向下取整为整天数；firstInboundAt 为 null 时返回 null。
 */
class StockAgeTest {

    /** 固定参考时间，保证边界断言稳定可复现 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 2, 12, 0, 0);

    /** firstInboundAt 为 null 时返回 null */
    @Test
    void nullFirstInboundAtReturnsNull() {
        assertNull(InventoryStock.stockAgeDays(null, NOW));
    }

    /** 当天入库（时间差不足 1 整天）向下取整为 0 天 */
    @Test
    void sameDayReturnsZero() {
        assertEquals(0L, InventoryStock.stockAgeDays(NOW.minusHours(1), NOW));
    }

    /** 昨天入库（刚好 1 整天）为 1 天 */
    @Test
    void exactlyOneDayReturnsOne() {
        assertEquals(1L, InventoryStock.stockAgeDays(NOW.minusDays(1), NOW));
    }

    /** 差 1 分钟满 1 天仍向下取整为 0 天 */
    @Test
    void justUnderOneDayFloorsToZero() {
        assertEquals(0L, InventoryStock.stockAgeDays(NOW.minusDays(1).plusMinutes(1), NOW));
    }

    /** 多天入库向下取整，忽略不足 1 天的零头 */
    @Test
    void multipleDaysFloorsDown() {
        assertEquals(5L, InventoryStock.stockAgeDays(NOW.minusDays(5).minusHours(3), NOW));
    }
}
