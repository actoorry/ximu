package com.by.ximu.inventory.common;

import java.util.function.Supplier;

/**
 * 短退避重试支撑（R2-P2-23 撞唯一键后败方重查前使用）。
 *
 * <p>并发同 requestId 双插时，败方撞 {@code DuplicateKeyException} 后立即回查大概率读不到
 * 对手尚未提交的行（对手事务仍持锁）——sleep 200ms 等对手提交后再回查一次，提升幂等命中率。
 * 原四个单据 Service 各持一份相同的 {@code sleepQuietly()} 私有方法，统一上收至此。
 */
public final class RetrySupport {

    private RetrySupport() {
    }

    /** 短暂退避 200ms；被中断时恢复中断标志（不吞中断状态）。 */
    public static void sleepQuietly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 撞键后幂等重试：立即回查 → {@link #sleepQuietly()} 退避 200ms → 再回查一次；
     * 命中返回已存在单据，仍查不到抛 {@link IllegalStateException} 并发冲突。
     *
     * <p>原四个单据 Service 的 create() 撞键 catch 块各持一份相同的「回查 → sleep → 再回查 → 抛并发」逻辑，统一上收。
     *
     * @param lookup 幂等回查函数（如 {@code () -> findByIdempotent(requestId)}）
     */
    public static <T> T retryIdempotent(Supplier<T> lookup) {
        T existed = lookup.get();
        if (existed == null) {
            sleepQuietly();
            existed = lookup.get();
        }
        if (existed != null) {
            return existed;
        }
        throw new IllegalStateException("并发重复请求，请稍后重试");
    }
}
