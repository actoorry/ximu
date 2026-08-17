package com.by.ximu.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 校验全局过滤器：校验 Authorization 头 → 剥离客户端伪造的 X-User-* → 注入可信身份头。
 *
 * <p>下游服务从 X-User-Id / X-User-Name / X-User-Roles 读取身份，不再信任请求体 operator。
 * 白名单路径（skip-prefixes，默认 /actuator）跳过校验。
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${app.jwt.secret}")
    private String secret;

    /** 内部共享令牌：网关校验 JWT 后注入，下游服务据此确认请求确经网关（防直连伪造 X-User-*） */
    @Value("${app.gateway-token:}")
    private String gatewayToken;

    @Value("${app.auth.skip-prefixes:/actuator}")
    private String skipPrefixes;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        for (String prefix : skipPrefixes.split(",")) {
            if (path.startsWith(prefix)) {
                return chain.filter(exchange);
            }
        }

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }
        String token = auth.substring(7);
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            // jjwt 0.12.x parseSignedClaims 默认只验签名不验 exp，必须显式校验过期时间
            if (claims.getExpiration() != null && claims.getExpiration().before(new java.util.Date())) {
                return unauthorized(exchange);
            }

            Object uid = claims.get("userId");
            String userId = uid == null ? "" : String.valueOf(uid);
            Object uname = claims.get("userName");
            String userName = uname == null ? "" : String.valueOf(uname);
            String roles = extractRoles(claims.get("roles"));

            // 显式剥离客户端伪造的身份头，再注入网关校验后的可信值
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(h -> {
                        h.remove("X-User-Id");
                        h.remove("X-User-Name");
                        h.remove("X-User-Roles");
                        h.remove("X-Gateway-Token");
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

    private String extractRoles(Object rolesClaim) {
        if (rolesClaim == null) {
            return "";
        }
        if (rolesClaim instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(rolesClaim);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
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
