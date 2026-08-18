package com.by.ximu.common;

/**
 * 权限校验工具：基于 {@link OperatorContext} 做角色校验与职责分离（制单 ≠ 审批/审核）。
 *
 * <p>权限不满足时抛 {@link ForbiddenException}，由各服务全局异常处理器映射为 403。
 */
public final class Auths {

    private Auths() {
    }

    /**
     * 要求当前操作人具备给定角色之一。
     *
     * <p>ADMIN 内置旁路：与 {@link #requireCreatorOrAdmin} / {@link #requireNotSelfOrAdmin} 一致，
     * 管理员通过任意角色要求，避免调用点漏传 {@code Role.ADMIN} 时把管理员锁死。
     */
    public static void requireRole(Role... roles) {
        Operator operator = OperatorContext.get();
        if (operator == null || operator.roles() == null || operator.roles().isEmpty()) {
            throw new ForbiddenException("未认证或缺少角色信息");
        }
        if (isAdmin(operator)) {
            return;
        }
        for (Role role : roles) {
            if (operator.roles().contains(role.name())) {
                return;
            }
        }
        throw new ForbiddenException("无权限执行该操作");
    }

    /** 仅本人（制单人）或管理员可操作该单据 */
    public static void requireCreatorOrAdmin(Long createdBy) {
        Operator operator = OperatorContext.get();
        if (operator == null || operator.id() == null) {
            throw new ForbiddenException("未认证");
        }
        if (isAdmin(operator)) {
            return;
        }
        if (operator.id().equals(createdBy)) {
            return;
        }
        throw new ForbiddenException("只能操作本人创建的单据");
    }

    /**
     * 职责分离：制单人不得审批/审核/完成自己的单据（管理员除外）。
     *
     * <p>{@code createdBy=null}（历史数据/异常数据）视为制单人不可追溯：非 ADMIN 一律拒绝（P2-14，
     * 原逻辑对 null 直接放行——职责分离在"不知道单据是谁建的"时无从谈起，宁可拒绝）。
     */
    public static void requireNotSelfOrAdmin(Long createdBy) {
        Operator operator = OperatorContext.get();
        if (operator == null || operator.id() == null) {
            throw new ForbiddenException("未认证");
        }
        if (isAdmin(operator)) {
            return;
        }
        if (createdBy == null) {
            throw new ForbiddenException("单据缺少创建人信息，仅管理员可审批/审核");
        }
        if (createdBy.equals(operator.id())) {
            throw new ForbiddenException("制单人与审批/审核人不能为同一人");
        }
    }

    private static boolean isAdmin(Operator operator) {
        return operator.roles() != null && operator.roles().contains(Role.ADMIN.name());
    }
}
