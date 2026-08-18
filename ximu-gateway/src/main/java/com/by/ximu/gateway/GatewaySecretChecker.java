package com.by.ximu.gateway;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 启动期密钥强度校验（P0-2）：弱密钥直接拒绝启动，杜绝"忘配/沿用泄露默认值"的服务带病上线。
 *
 * <p>用 {@code @PostConstruct} 在 Bean 初始化期校验——早于 Web 服务器开始监听，
 * 失败时端口从未对外打开（ApplicationRunner 阶段服务器已短暂监听，弃用）。
 * 对所有 profile 生效（dev profile 的兜底密钥满足强度要求）；
 * 校验项：JWT 密钥非空、不在弱密钥黑名单、长度 >= 32 字节；网关共享令牌非空。
 */
@Component
public class GatewaySecretChecker {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecretChecker.class);

    /** 弱密钥黑名单：曾提交进 git 的默认值（视为已泄露）及一切 change-me 前缀占位值 */
    private static final String LEAKED_DEFAULT_SECRET = "change-me-please-use-a-32-byte-min-secret-key";
    private static final List<String> WEAK_PREFIXES = List.of("change-me", "please-change", "your-secret", "test-secret");
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.gateway-token}")
    private String gatewayToken;

    @PostConstruct
    void check() {
        // 网关共享令牌：必填（缺失时无法证明请求确经网关，下游防线形同虚设）
        if (gatewayToken == null || gatewayToken.isBlank()) {
            throw new IllegalStateException("GATEWAY_TOKEN 未配置：网关必须注入 X-Gateway-Token 才能向下游证明请求身份，拒绝启动");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET 未配置：拒绝以空密钥启动（可自签任意身份 token）");
        }
        if (LEAKED_DEFAULT_SECRET.equals(jwtSecret) || WEAK_PREFIXES.stream().anyMatch(jwtSecret::startsWith)) {
            throw new IllegalStateException("JWT_SECRET 为已知弱密钥/泄露默认值（旧默认密钥已进 git 历史视为泄露）："
                    + "请用 openssl rand -base64 48 生成新密钥后重启");
        }
        int bytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET 长度不足：" + bytes + " 字节（HS256 要求 >= " + MIN_SECRET_BYTES + " 字节）");
        }
        log.info("网关密钥强度校验通过（secret {} 字节，token 已配置）", bytes);
    }
}
