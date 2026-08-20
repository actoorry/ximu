package com.by.ximu.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link GatewaySecretChecker} P0-2 启动期密钥强度校验回归测试。
 *
 * <p>校验项：GATEWAY_TOKEN 非空、JWT_SECRET 非空、JWT_SECRET 不在弱密钥黑名单
 * （泄露默认值 / change-me 系前缀占位值，前缀比较小写不敏感）、长度 >= 32 字节。
 *
 * <p>范式：同包直调 package-private {@code check()}（@PostConstruct 由 Spring 容器触发，
 * 单测里手工调用）；弱密钥任一条件命中即抛 {@link IllegalStateException} 拒绝启动。
 */
class GatewaySecretCheckerTest {

    /** 长度 >= 32 字节、且不在任何弱前缀/泄露默认值黑名单内的合法密钥 */
    private static final String VALID_SECRET = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
    private static final String VALID_TOKEN = "gateway-token-0123456789";

    private GatewaySecretChecker checker;

    @BeforeEach
    void setUp() {
        checker = new GatewaySecretChecker();
    }

    @Test
    void gatewayToken空白_拒绝启动() {
        ReflectionTestUtils.setField(checker, "jwtSecret", VALID_SECRET);
        ReflectionTestUtils.setField(checker, "gatewayToken", "");
        assertThrows(IllegalStateException.class, checker::check);
    }

    @Test
    void jwtSecret空白_拒绝启动() {
        ReflectionTestUtils.setField(checker, "jwtSecret", "");
        ReflectionTestUtils.setField(checker, "gatewayToken", VALID_TOKEN);
        assertThrows(IllegalStateException.class, checker::check);
    }

    @Test
    void jwtSecret等于泄露默认值_拒绝启动() {
        ReflectionTestUtils.setField(checker, "jwtSecret", "change-me-please-use-a-32-byte-min-secret-key");
        ReflectionTestUtils.setField(checker, "gatewayToken", VALID_TOKEN);
        assertThrows(IllegalStateException.class, checker::check);
    }

    @Test
    void jwtSecret大写CHANGE_ME前缀_拒绝启动() {
        // P2-4：前缀比较前统一小写，运维配成 "CHANGE-ME-..." 大写变体也不得漏过黑名单
        ReflectionTestUtils.setField(checker, "jwtSecret", "CHANGE-ME-upper-case-variant-secret-1234567890");
        ReflectionTestUtils.setField(checker, "gatewayToken", VALID_TOKEN);
        assertThrows(IllegalStateException.class, checker::check);
    }

    @Test
    void jwtSecret其他弱前缀_拒绝启动() {
        List<String> weakPrefixes = List.of("please-change", "your-secret", "test-secret");
        for (String prefix : weakPrefixes) {
            ReflectionTestUtils.setField(checker, "jwtSecret", prefix + "-0123456789abcdef0123456789");
            ReflectionTestUtils.setField(checker, "gatewayToken", VALID_TOKEN);
            assertThrows(IllegalStateException.class, checker::check,
                    "弱前缀 " + prefix + " 应被拒绝启动");
        }
    }

    @Test
    void jwtSecret长度不足32字节_拒绝启动() {
        ReflectionTestUtils.setField(checker, "jwtSecret", "short-secret");
        ReflectionTestUtils.setField(checker, "gatewayToken", VALID_TOKEN);
        assertThrows(IllegalStateException.class, checker::check);
    }

    @Test
    void 合法密钥与非空token_通过校验() {
        ReflectionTestUtils.setField(checker, "jwtSecret", VALID_SECRET);
        ReflectionTestUtils.setField(checker, "gatewayToken", VALID_TOKEN);
        assertDoesNotThrow(checker::check);
    }
}
