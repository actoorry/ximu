package com.by.ximu.common.web.security;

import com.by.ximu.common.ForbiddenException;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * {@link RequireRole} 注解的 AOP 切面：方法调用前校验操作人角色，不满足抛 {@link ForbiddenException}（403）。
 *
 * <p>与 {@code com.by.ximu.common.Auths#requireRole} 语义对齐：ADMIN 内置旁路；
 * 上下文未设置（{@link OperatorContext#hasAnyRole} 返回 false）即拒绝——fail-closed。
 */
@Aspect
@Component
public class RequireRoleAspect {

    @Around("@annotation(requireRole)")
    public Object checkRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        // ADMIN 内置旁路（对齐 Auths 文档化行为）
        boolean admin = OperatorContext.hasRole(Role.ADMIN.name());
        boolean allowed = admin || OperatorContext.hasAnyRole(requireRole.value());
        if (!allowed) {
            throw new ForbiddenException("无权限执行该操作");
        }
        return joinPoint.proceed();
    }
}
