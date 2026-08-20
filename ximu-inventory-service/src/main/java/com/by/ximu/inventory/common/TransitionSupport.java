package com.by.ximu.inventory.common;

import java.util.Map;
import java.util.Set;

/**
 * PUT /{id}「流转 vs 编辑」判定支撑（R2-P1-3）。
 *
 * <p>原判定 {@code containsKey("action") && allMatch(TRANSITION_KEYS)} 的问题：body 多传任意额外字段
 * （如 {@code remark}）→ 判定为普通编辑 → {@code action} 被 DTO 绑定静默忽略 → 返回成功但单据未流转
 * （前端以为已 approve/check，实际未生效）。
 * <p>现改为：只要含 {@code action} 即走流转；流转请求体含 TRANSITION_KEYS 之外字段一律 400，杜绝静默降级。
 */
public final class TransitionSupport {

    private TransitionSupport() {
    }

    /** 是否流转请求：body 含 {@code action} 字段即视为流转（不再要求全部字段落在白名单内） */
    public static boolean isTransition(Map<String, Object> body) {
        return body != null && body.containsKey("action");
    }

    /** 流转请求体校验：仅允许 {@code transitionKeys} 内的字段，多余字段显式 400（防静默降级为普通编辑） */
    public static void requireTransitionBody(Map<String, Object> body, Set<String> transitionKeys) {
        if (body == null) {
            return;
        }
        for (String key : body.keySet()) {
            if (!transitionKeys.contains(key)) {
                throw new IllegalArgumentException("流转请求不允许包含字段: " + key);
            }
        }
    }
}
