package com.by.ximu.gateway;

import com.by.ximu.common.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParserBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JWT 校验全局过滤器：校验 Authorization 头 → 剥离客户端伪造的 X-User-* → 注入可信身份头。
 *
 * <p>下游服务从 X-User-Id / X-User-Name / X-User-Roles 读取身份，不再信任请求体 operator。
 * 白名单路径（skip-prefixes，默认 /actuator）跳过校验。
 *
 * <p>令牌声明收紧（P2-4）：
 * <ul>
 *   <li>roles 白名单：claim 值（列表或逗号串）逐个过 {@link Role} 枚举名白名单，未知/含逗号
 *       的畸形值直接丢弃——角色数据源被污染（角色名里混入逗号）也不会在拼接/拆分中造出新角色；</li>
 *   <li>iss/aud 校验：配置了 app.jwt.issuer / audience 时强制匹配（防其他系统签发的同密钥
 *       token 混用），留空不校验（向后兼容存量签发方）。</li>
 * </ul>
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${app.jwt.secret}")
    private String secret;

    /** 可选：签发方声明校验（配置非空即强制 requireIssuer） */
    @Value("${app.jwt.issuer:}")
    private String issuer;

    /** 可选：受众声明校验（配置非空即强制 requireAudience） */
    @Value("${app.jwt.audience:}")
    private String audience;

    /** 内部共享令牌：网关校验 JWT 后注入，下游服务据此确认请求确经网关（防直连伪造 X-User-*） */
    @Value("${app.gateway-token:}")
    private String gatewayToken;

    @Value("${app.auth.skip-prefixes:/actuator}")
    private String skipPrefixes;

    /** 合法角色白名单（与 ximu-common Role 枚举单一来源同步） */
    private static final Set<String> KNOWN_ROLES =
            Arrays.stream(Role.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isSkippable(path)) {
            // R2-P1-5：白名单路径跳过 JWT 校验但仍剥离客户端伪造的身份头与 Authorization，
            // 防止客户端把伪造的 X-User-*/X-Gateway-Token 头一路带到下游服务
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(JwtAuthGlobalFilter::stripIdentityHeaders)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }
        String token = auth.substring(7);
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            JwtParserBuilder parser = Jwts.parser().verifyWith(key);
            if (StringUtils.hasText(issuer)) {
                parser.requireIssuer(issuer);
            }
            if (StringUtils.hasText(audience)) {
                parser.requireAudience(audience);
            }
            Claims claims = parser.build().parseSignedClaims(token).getPayload();
            // 严格过期校验：exp 必须存在且未过期，否则拒绝（防签发方漏设 exp 导致 token 永久有效）
            if (claims.getExpiration() == null || claims.getExpiration().before(new java.util.Date())) {
                return unauthorized(exchange);
            }

            Object uid = claims.get("userId");
            String userId = uid == null ? "" : String.valueOf(uid);
            Object uname = claims.get("userName");
            String userName = uname == null ? "" : String.valueOf(uname);
            String roles = extractRoles(claims.get("roles"));

            // 显式剥离客户端伪造的身份头与原始 Authorization，再注入网关校验后的可信值
            // （P2-1：Authorization 不剥离则把原始签名 token 泄露给下游，形成「下游可读取原始凭据」的过度暴露）
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(h -> {
                        JwtAuthGlobalFilter.stripIdentityHeaders(h);
                        h.add("X-User-Id", userId);
                        h.add("X-User-Name", userName);
                        h.add("X-User-Roles", roles);
                        if (gatewayToken != null && !gatewayToken.isBlank()) {
                            h.add("X-Gateway-Token", gatewayToken);
                        }
                    })
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return unauthorized(exchange);
        }
    }

    /**
     * roles claim 提取 + 白名单过滤（P2-4）。
     *
     * <p>claim 为列表时逐项过滤；为字符串时按逗号拆分再逐项过滤（与下游 OperatorContextFilter 的
     * split(",") 语义对齐）。任何不在 {@link Role} 枚举内的值（含整体带逗号的串）一律丢弃，
     * 杜绝「畸形角色值经拼接/拆分组合出 ADMIN」的注入面。
     */
    private String extractRoles(Object rolesClaim) {
        if (rolesClaim == null) {
            return "";
        }
        Stream<String> values = rolesClaim instanceof List<?> list
                ? list.stream().map(String::valueOf)
                : Arrays.stream(String.valueOf(rolesClaim).split(","));
        return values.map(String::trim)
                .filter(KNOWN_ROLES::contains)
                .collect(Collectors.joining(","));
    }

    /**
     * 白名单前缀精确段匹配（R2-P1-5）：仅当 path 逐段等于任一前缀（或为其同段子路径）且
     * 剩余段不含 "." / ".." 时放行。原 {@code startsWith(prefix)} 会把 "/actuator/../api/inbound"
     * 之类含 {@code ..} 段的路径误判为命中白名单，绕过 JWT 校验直通业务接口。
     * 编码形式（%2e）由下游 OperatorContextFilter 的 {@code getServletPath()}（解码后判定 /api/）兜底。
     */
    private boolean isSkippable(String path) {
        for (String prefix : skipPrefixes.split(",")) {
            String p = prefix.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (isPathSegmentMatch(path, p)) {
                return true;
            }
        }
        return false;
    }

    /** path 与白名单前缀按段精确匹配：等于前缀，或前缀后紧跟 "/" 且剩余段无 "." / ".." */
    private boolean isPathSegmentMatch(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        if (path.length() == prefix.length()) {
            return true;
        }
        // 前缀后必须紧跟 "/"（防 "/actuatorX" 误命中 "/actuator" 白名单）；剩余段逐个拒绝 "." / ".."
        if (path.charAt(prefix.length()) != '/') {
            return false;
        }
        for (String seg : path.substring(prefix.length()).split("/")) {
            if (".".equals(seg) || "..".equals(seg)) {
                return false;
            }
        }
        return true;
    }

    /** 剥离客户端可伪造的身份/凭据头（白名单跳过路径与正常校验路径共用；P2-1 顺带剥离 Authorization） */
    private static void stripIdentityHeaders(HttpHeaders headers) {
        headers.remove("X-User-Id");
        headers.remove("X-User-Name");
        headers.remove("X-User-Roles");
        headers.remove("X-Gateway-Token");
        headers.remove(HttpHeaders.AUTHORIZATION);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        // P2-5：补 Content-Type，下游/前端按 JSON 解析错误体（原响应无 Content-Type，部分客户端解析失败）
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = "{\"code\":401,\"message\":\"未认证或令牌无效\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100; // 在路由转发前执行
    }
}
