package com.by.ximu.common;

import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Auths} 权限校验工具的纯单元测试。
 *
 * <p>被测契约（与现状一致，勿随意改动）：</p>
 * <ul>
 *     <li>roles 存储的是角色枚举的 {@code name()}（如 {@code "ADMIN"}），匹配大小写敏感，小写 {@code "admin"} 不命中。</li>
 *     <li>{@code requireRole} 内置 ADMIN 旁路——ADMIN 通过任意角色要求（见下方「契约固化」用例，与
 *     {@code requireCreatorOrAdmin / requireNotSelfOrAdmin} 的 isAdmin 旁路一致）。</li>
 * </ul>
 */
class AuthsTest {

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    /** 构造指定角色的操作人（roles 存枚举 name()） */
    private static Operator operator(Long id, Role... roles) {
        return new Operator(id, "测试用户", Arrays.stream(roles).map(Role::name).toList());
    }

    // ---------- requireRole ----------

    @Test
    void requireRole_操作人具备要求角色时通过() {
        OperatorContext.set(operator(1L, Role.CREATOR));
        assertThatCode(() -> Auths.requireRole(Role.CREATOR)).doesNotThrowAnyException();
    }

    @Test
    void requireRole_操作人不具备要求角色时抛ForbiddenException() {
        OperatorContext.set(operator(1L, Role.CREATOR));
        assertThatThrownBy(() -> Auths.requireRole(Role.CHECKER))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("无权限执行该操作");
    }

    @Test
    void requireRole_未设置OperatorContext时抛ForbiddenException() {
        // 不 set，OperatorContext.get() 返回 null
        assertThatThrownBy(() -> Auths.requireRole(Role.CREATOR))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("未认证或缺少角色信息");
    }

    @Test
    void requireRole_角色列表为空时抛ForbiddenException() {
        OperatorContext.set(operator(1L));
        assertThatThrownBy(() -> Auths.requireRole(Role.CREATOR))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("未认证或缺少角色信息");
    }

    @Test
    void requireRole_ADMIN角色通过ADMIN角色要求() {
        OperatorContext.set(operator(1L, Role.ADMIN));
        assertThatCode(() -> Auths.requireRole(Role.ADMIN)).doesNotThrowAnyException();
    }

    /**
     * 契约固化：requireRole 内置 ADMIN 旁路——ADMIN 通过任意角色要求
     * （与 requireCreatorOrAdmin / requireNotSelfOrAdmin 的 isAdmin 旁路一致）。
     */
    @Test
    void requireRole_ADMIN旁路_通过其他角色要求() {
        OperatorContext.set(operator(1L, Role.ADMIN));
        assertThatCode(() -> Auths.requireRole(Role.CREATOR)).doesNotThrowAnyException();
        assertThatCode(() -> Auths.requireRole(Role.CHECKER, Role.APPROVER)).doesNotThrowAnyException();
    }

    // ---------- requireCreatorOrAdmin ----------

    @Test
    void requireCreatorOrAdmin_管理员通过_即使不是本人() {
        OperatorContext.set(operator(1L, Role.ADMIN));
        assertThatCode(() -> Auths.requireCreatorOrAdmin(999L)).doesNotThrowAnyException();
    }

    @Test
    void requireCreatorOrAdmin_本人通过() {
        OperatorContext.set(operator(1L, Role.CREATOR));
        assertThatCode(() -> Auths.requireCreatorOrAdmin(1L)).doesNotThrowAnyException();
    }

    @Test
    void requireCreatorOrAdmin_他人操作时抛ForbiddenException() {
        OperatorContext.set(operator(1L, Role.CREATOR));
        assertThatThrownBy(() -> Auths.requireCreatorOrAdmin(2L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("只能操作本人创建的单据");
    }

    @Test
    void requireCreatorOrAdmin_未认证时抛ForbiddenException() {
        assertThatThrownBy(() -> Auths.requireCreatorOrAdmin(1L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("未认证");
    }

    // ---------- requireNotSelfOrAdmin ----------

    @Test
    void requireNotSelfOrAdmin_管理员通过_即使是自己制单() {
        OperatorContext.set(operator(1L, Role.ADMIN));
        assertThatCode(() -> Auths.requireNotSelfOrAdmin(1L)).doesNotThrowAnyException();
    }

    @Test
    void requireNotSelfOrAdmin_制单人自己操作时抛ForbiddenException() {
        OperatorContext.set(operator(1L, Role.CREATOR));
        assertThatThrownBy(() -> Auths.requireNotSelfOrAdmin(1L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("制单人与审批/审核人不能为同一人");
    }

    @Test
    void requireNotSelfOrAdmin_他人操作时通过() {
        OperatorContext.set(operator(1L, Role.CREATOR));
        assertThatCode(() -> Auths.requireNotSelfOrAdmin(2L)).doesNotThrowAnyException();
    }

    @Test
    void requireNotSelfOrAdmin_未认证时抛ForbiddenException() {
        assertThatThrownBy(() -> Auths.requireNotSelfOrAdmin(1L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("未认证");
    }

    /** P2-14：createdBy=null（历史/异常数据）制单人不可追溯，非 ADMIN 一律拒绝（原逻辑静默放行） */
    @Test
    void requireNotSelfOrAdmin_单据无创建人_非管理员拒绝() {
        OperatorContext.set(operator(1L, Role.APPROVER));
        assertThatThrownBy(() -> Auths.requireNotSelfOrAdmin(null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("单据缺少创建人信息，仅管理员可审批/审核");
    }

    /** P2-14：createdBy=null 时 ADMIN 仍可操作（管理员旁路不受影响） */
    @Test
    void requireNotSelfOrAdmin_单据无创建人_管理员通过() {
        OperatorContext.set(operator(1L, Role.ADMIN));
        assertThatCode(() -> Auths.requireNotSelfOrAdmin(null)).doesNotThrowAnyException();
    }

    // ---------- ForbiddenException（并入本类） ----------

    @Test
    void forbiddenException_message传递正确() {
        ForbiddenException ex = new ForbiddenException("无权限");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("无权限");
    }
}
