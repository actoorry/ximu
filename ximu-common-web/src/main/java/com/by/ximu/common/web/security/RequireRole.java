package com.by.ximu.common.web.security;

import com.by.ximu.common.Role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法级 RBAC 角色要求注解：标注于 Controller 端点方法，
 * 由 {@link RequireRoleAspect} 切面在调用前校验操作人是否具备任一所需角色。
 *
 * <p>纯角色校验（满足任一角色即可，ADMIN 内置旁路）；职责分离类校验
 * （{@code requireNotSelfOrAdmin} / {@code requireCreatorOrAdmin}）留在 Service 事务内执行，
 * 本注解不覆盖。漏标即 fail-closed（切面在无上下文时同样拒绝）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /** 允许的角色（满足任一即通过），ADMIN 恒旁路 */
    Role[] value();
}
