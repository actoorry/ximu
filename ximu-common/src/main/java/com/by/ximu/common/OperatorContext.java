package com.by.ximu.common;

import java.util.List;

/**
 * 当前请求操作人上下文（ThreadLocal）。
 *
 * <p>由各服务的 OperatorContextFilter 在请求入口写入，请求结束后清理；
 * 业务代码通过 {@link #getOperatorName()} / {@link #get()} 读取，禁止再信任请求体中的 operator。
 */
public final class OperatorContext {

    private static final ThreadLocal<Operator> HOLDER = new ThreadLocal<>();

    private OperatorContext() {
    }

    public static void set(Operator operator) {
        HOLDER.set(operator);
    }

    public static Operator get() {
        return HOLDER.get();
    }

    /** 操作人名称，未认证时返回 null */
    public static String getOperatorName() {
        Operator operator = HOLDER.get();
        return operator == null ? null : operator.name();
    }

    /** 操作人 ID，未认证时返回 null */
    public static Long getOperatorId() {
        Operator operator = HOLDER.get();
        return operator == null ? null : operator.id();
    }

    /** 操作人角色列表，未认证时返回空列表 */
    public static List<String> getRoles() {
        Operator operator = HOLDER.get();
        return operator == null || operator.roles() == null ? List.of() : operator.roles();
    }

    public static boolean hasRole(String role) {
        Operator operator = HOLDER.get();
        return operator != null && operator.roles() != null && operator.roles().contains(role);
    }

    public static boolean hasAnyRole(Role... roles) {
        Operator operator = HOLDER.get();
        if (operator == null || operator.roles() == null) {
            return false;
        }
        for (Role role : roles) {
            if (operator.roles().contains(role.name())) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
