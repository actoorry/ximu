package com.by.ximu.common.web.security;

import com.by.ximu.common.ForbiddenException;
import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequireRoleAspect} 纯 Mockito 测试（不启 Spring 容器）。
 *
 * <p>覆盖四个分支：无上下文（fail-closed）/ 角色不匹配 / 角色匹配 / ADMIN 旁路。
 */
class RequireRoleAspectTest {

    private final RequireRoleAspect aspect = new RequireRoleAspect();
    private ProceedingJoinPoint pjp;
    private RequireRole annotation;

    @BeforeEach
    void setUp() {
        pjp = mock(ProceedingJoinPoint.class);
        annotation = mock(RequireRole.class);
        when(annotation.value()).thenReturn(new Role[]{Role.CREATOR});
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    @Test
    void rejectsWhenNoContext() throws Throwable {
        OperatorContext.clear();
        assertThatThrownBy(() -> aspect.checkRole(pjp, annotation))
                .isInstanceOf(ForbiddenException.class);
        verify(pjp, never()).proceed();
    }

    @Test
    void rejectsWhenRoleMismatch() throws Throwable {
        OperatorContext.set(new Operator(1L, "viewer", List.of(Role.VIEWER.name())));
        assertThatThrownBy(() -> aspect.checkRole(pjp, annotation))
                .isInstanceOf(ForbiddenException.class);
        verify(pjp, never()).proceed();
    }

    @Test
    void proceedsWhenRoleMatches() throws Throwable {
        OperatorContext.set(new Operator(1L, "creator", List.of(Role.CREATOR.name())));
        aspect.checkRole(pjp, annotation);
        verify(pjp).proceed();
    }

    @Test
    void adminBypassesEvenWhenAnnotationLacksAdmin() throws Throwable {
        OperatorContext.set(new Operator(1L, "admin", List.of(Role.ADMIN.name())));
        aspect.checkRole(pjp, annotation);
        verify(pjp).proceed();
    }
}
