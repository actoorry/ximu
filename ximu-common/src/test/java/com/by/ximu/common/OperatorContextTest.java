package com.by.ximu.common;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OperatorContext} ThreadLocal 上下文的纯单元测试。
 *
 * <p>hasRole 为字符串入参，hasAnyRole 为 {@link Role} 枚举入参；两种入参形式均覆盖。</p>
 */
class OperatorContextTest {

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private static Operator operator(Long id, String name, List<String> roles) {
        return new Operator(id, name, roles);
    }

    // ---------- set / get ----------

    @Test
    void set与get_返回同一实例() {
        Operator op = operator(1L, "张三", List.of(Role.ADMIN.name()));
        OperatorContext.set(op);
        assertThat(OperatorContext.get()).isSameAs(op);
    }

    // ---------- getOperatorName ----------

    @Test
    void getOperatorName_已设置时返回name() {
        OperatorContext.set(operator(1L, "张三", List.of()));
        assertThat(OperatorContext.getOperatorName()).isEqualTo("张三");
    }

    @Test
    void getOperatorName_未设置时返回null() {
        assertThat(OperatorContext.getOperatorName()).isNull();
    }

    // ---------- getOperatorId ----------

    @Test
    void getOperatorId_已设置时返回id() {
        OperatorContext.set(operator(42L, "张三", List.of()));
        assertThat(OperatorContext.getOperatorId()).isEqualTo(42L);
    }

    @Test
    void getOperatorId_未设置时返回null() {
        assertThat(OperatorContext.getOperatorId()).isNull();
    }

    // ---------- getRoles ----------

    @Test
    void getRoles_已设置时返回角色列表() {
        OperatorContext.set(operator(1L, "张三", List.of("ADMIN", "CREATOR")));
        assertThat(OperatorContext.getRoles()).containsExactly("ADMIN", "CREATOR");
    }

    @Test
    void getRoles_角色为null时返回空列表() {
        OperatorContext.set(operator(1L, "张三", null));
        assertThat(OperatorContext.getRoles()).isEmpty();
    }

    @Test
    void getRoles_未设置时返回空列表() {
        assertThat(OperatorContext.getRoles()).isEmpty();
    }

    // ---------- hasRole（字符串入参） ----------

    @Test
    void hasRole_字符串入参命中返回true() {
        OperatorContext.set(operator(1L, "张三", List.of("ADMIN")));
        assertThat(OperatorContext.hasRole("ADMIN")).isTrue();
        assertThat(OperatorContext.hasRole("CREATOR")).isFalse();
    }

    @Test
    void hasRole_枚举name字符串入参命中返回true() {
        OperatorContext.set(operator(1L, "张三", List.of(Role.ADMIN.name())));
        assertThat(OperatorContext.hasRole(Role.ADMIN.name())).isTrue();
        assertThat(OperatorContext.hasRole(Role.CREATOR.name())).isFalse();
    }

    /**
     * 契约固化：角色匹配大小写敏感——roles 存 {@code "ADMIN"} 才命中，小写 {@code "admin"} 不命中。
     */
    @Test
    void hasRole_大小写敏感_小写admin不命中() {
        OperatorContext.set(operator(1L, "张三", List.of("ADMIN")));
        assertThat(OperatorContext.hasRole("admin")).isFalse();
        assertThat(OperatorContext.hasRole("ADMIN")).isTrue();
    }

    @Test
    void hasRole_角色为null时返回false() {
        OperatorContext.set(operator(1L, "张三", null));
        assertThat(OperatorContext.hasRole("ADMIN")).isFalse();
    }

    @Test
    void hasRole_未设置时返回false() {
        assertThat(OperatorContext.hasRole("ADMIN")).isFalse();
    }

    // ---------- hasAnyRole（枚举入参） ----------

    @Test
    void hasAnyRole_枚举入参命中返回true() {
        OperatorContext.set(operator(1L, "张三", List.of("CREATOR")));
        assertThat(OperatorContext.hasAnyRole(Role.ADMIN, Role.CREATOR)).isTrue();
        assertThat(OperatorContext.hasAnyRole(Role.ADMIN, Role.CHECKER)).isFalse();
    }

    @Test
    void hasAnyRole_角色为null时返回false() {
        OperatorContext.set(operator(1L, "张三", null));
        assertThat(OperatorContext.hasAnyRole(Role.ADMIN)).isFalse();
    }

    @Test
    void hasAnyRole_未设置时返回false() {
        assertThat(OperatorContext.hasAnyRole(Role.ADMIN)).isFalse();
    }

    // ---------- clear ----------

    @Test
    void clear_后全部清空() {
        OperatorContext.set(operator(1L, "张三", List.of("ADMIN")));
        OperatorContext.clear();
        assertThat(OperatorContext.get()).isNull();
        assertThat(OperatorContext.getOperatorName()).isNull();
        assertThat(OperatorContext.getOperatorId()).isNull();
        assertThat(OperatorContext.getRoles()).isEmpty();
        assertThat(OperatorContext.hasRole("ADMIN")).isFalse();
        assertThat(OperatorContext.hasAnyRole(Role.ADMIN)).isFalse();
    }
}
