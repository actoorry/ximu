package com.by.jxc.inventory.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * 单据号生成器（参照 jshERP buildOnlyNumber 适配微服务）。
 *
 * <p>规则：{@code 前缀 + yyyyMMdd + 3位序号}，例如 {@code IN20260814001}。
 * <ul>
 *   <li>入库前缀 {@code IN}、出库 {@code OUT}、盘点 {@code CK}、调拨 {@code TR}</li>
 *   <li>序号 = 当天同类单据已存在的最大序号 + 1，从 001 开始，左补零 3 位</li>
 * </ul>
 *
 * <p>{@code generate} 使用 {@code synchronized} 保证单机并发安全；
 * 多实例部署时还需配合分布式锁 + 单号唯一索引兜底。
 */
public final class DocNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DocNoGenerator() {
    }

    /**
     * 生成单据号。
     *
     * @param prefix 单据前缀（IN/OUT/CK/TR）
     * @param maxSeq 当天同类单据已存在的最大序号（可为 {@code null} 或 0，表示当天首张）
     * @return 形如 {@code IN20260814001} 的单号
     */
    public static synchronized String generate(String prefix, Long maxSeq) {
        long seq = (maxSeq == null ? 0L : maxSeq) + 1L;
        return prefix + LocalDate.now().format(DATE_FMT) + String.format("%03d", seq);
    }

    /**
     * 生成单据号（{@code long} 序号重载）。
     */
    public static synchronized String generate(String prefix, long maxSeq) {
        return prefix + LocalDate.now().format(DATE_FMT) + String.format("%03d", maxSeq + 1L);
    }

    /**
     * 从一批已存在的单据号中解析出最大序号。
     *
     * <p>单据号格式：{@code prefix + yyyyMMdd(8位) + 序号后缀}。
     * 非标准格式（长度不足或后缀非数字）的单号会被跳过，不影响其余解析。
     *
     * @param existingNos 已存在单据号集合（可为 {@code null}）
     * @param prefixLen   前缀长度，用于定位序号起始位置（= prefixLen + 8）
     * @return 最大序号；集合为空或无合法单号时返回 0
     */
    public static long maxSeqOf(Collection<String> existingNos, int prefixLen) {
        if (existingNos == null || existingNos.isEmpty()) {
            return 0L;
        }
        long max = 0L;
        for (String no : existingNos) {
            if (no == null || no.length() <= prefixLen + 8) {
                continue;
            }
            try {
                long s = Long.parseLong(no.substring(prefixLen + 8));
                if (s > max) {
                    max = s;
                }
            } catch (NumberFormatException ignore) {
                // 非标准单号格式，跳过
            }
        }
        return max;
    }
}
