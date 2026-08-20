package com.by.ximu.inventory.common;

/**
 * 单据流转/删除/编辑公共前置校验（保守去重，行为与原文案逐字一致）。
 *
 * <p>四个单据 Service（Inbound/Outbound/Check/Transfer）的状态机（approve/check/complete）、
 * 级联删除、编辑方法各持一份相同的「查存在 → 状态前置 → 乐观锁更新」骨架（原 9 个状态机方法 +
 * 4 个 deleteWithItems + 4 个 updateHead），统一上收至此；差异点（角色校验、库存联动、审计参数）留在各 Service。
 */
public final class DocGuard {

    private DocGuard() {
    }

    /**
     * 单据存在性校验：查不到抛 {@link IllegalArgumentException}。
     *
     * @param doc     查得单据实体（可为 null）
     * @param docType 单据类型文案（如「入库单」），用于报错文案
     * @param id      单据主键
     */
    public static <T> T requireExists(T doc, String docType, Long id) {
        if (doc == null) {
            throw new IllegalArgumentException(docType + "不存在: " + id);
        }
        return doc;
    }

    /**
     * 状态机前置校验：当前状态必须等于 {@code expected}，否则抛 {@link IllegalStateException}。
     *
     * <p>文案与原文案逐字一致：{@code 当前状态[current]不允许action，仅 expected 状态可action}。
     *
     * @param current  当前状态（如 {@code doc.getStatus()}）
     * @param expected 允许迁移的前置状态（如 {@code DocStatus.CREATED.name()}）
     * @param action   操作文案（批准/审核/完成/删除）
     */
    public static void requireTransitionStatus(String current, String expected, String action) {
        if (!expected.equals(current)) {
            throw new IllegalStateException("当前状态[" + current + "]不允许" + action + "，仅 " + expected + " 状态可" + action);
        }
    }

    /**
     * 乐观锁更新结果校验：更新影响 0 行即并发冲突，抛 {@link IllegalStateException} 提示刷新重试。
     *
     * @param updated MyBatis-Plus {@code updateById} 的返回值
     */
    public static void requireUpdateSucceeded(boolean updated) {
        if (!updated) {
            throw new IllegalStateException("单据已被他人操作，请刷新重试");
        }
    }
}
