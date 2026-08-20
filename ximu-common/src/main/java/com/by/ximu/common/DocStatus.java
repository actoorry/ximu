package com.by.ximu.common;

/**
 * 单据状态（状态机：CREATED → APPROVED → CHECKED → COMPLETED）。
 *
 * <p>与各单据实体 status 字段（String）映射，name() 与裸字符串逐字符一致；
 * {@link #from(String)} 解析未知状态返回 null，调用方按需处理。
 */
public enum DocStatus {
    /** 已制单（草稿/待批准） */
    CREATED,
    /** 已批准（待审核/待完成） */
    APPROVED,
    /** 已审核（待完成，仅调拨等需要二次流转的单据） */
    CHECKED,
    /** 已完成（终态） */
    COMPLETED;

    /** 按枚举名解析状态，未知状态返回 null */
    public static DocStatus from(String status) {
        if (status == null) {
            return null;
        }
        for (DocStatus docStatus : values()) {
            if (docStatus.name().equals(status)) {
                return docStatus;
            }
        }
        return null;
    }
}
