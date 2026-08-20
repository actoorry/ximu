package com.by.ximu.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JwtAuthGlobalFilter} P0-2 安全行为回归测试（WebFlux，纯 Mockito + MockServerWebExchange，无 @WebFluxTest）。
 *
 * <p>锁死契约：合法 token → 剥离伪造身份头并注入可信 X-User-* / X-Gateway-Token 后放行；
 * 过期 / alg:none / 缺 Authorization / 非 Bearer → 401 且不调下游链；roles 只保留 {@link com.by.ximu.common.Role}
 * 白名单内角色（未知/逗号畸形值丢弃）；白名单路径跳过 JWT 校验但仍剥离伪造 X-User-* 头。
 *
 * <p>范式：真实 {@link MockServerWebExchange}（spring-test 工具，避免手 mock 可变的 fluent Builder 链），
 * 仅 mock {@link GatewayFilterChain}；token 用与过滤器同 secret 的 jjwt 0.12 API 真实签发（勿 mock 静态 Jwts）。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthGlobalFilterTest {

    /** 与过滤器配置同源密钥（>= 32 字节） */
    private static final String SECRET = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
    private static final String GATEWAY_TOKEN = "gateway-token-123";

    /** alg:none 裸 token（无签名；verifyWith 配置下必然拒签） */
    private static final String NONE_ALG_TOKEN = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.";

    @Mock
    private GatewayFilterChain chain;

    private JwtAuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthGlobalFilter();
        ReflectionTestUtils.setField(filter, "secret", SECRET);
        ReflectionTestUtils.setField(filter, "issuer", "");
        ReflectionTestUtils.setField(filter, "audience", "");
        ReflectionTestUtils.setField(filter, "gatewayToken", GATEWAY_TOKEN);
        ReflectionTestUtils.setField(filter, "skipPrefixes", "/actuator");
    }

    @Test
    void 合法token_剥离伪造头并注入可信身份头放行() {
        String token = sign("1", 1L, "操作员", List.of("VIEWER"), future());
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/inventory/stock")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "forged-999")   // 客户端伪造头，应被剥离替换
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        filter.filter(exchange, chain);

        ServerWebExchange mutated = capturedMutatedExchange();
        HttpHeaders headers = mutated.getRequest().getHeaders();
        assertEquals("1", headers.getFirst("X-User-Id"), "注入可信 userId");
        assertEquals("操作员", headers.getFirst("X-User-Name"), "注入可信 userName");
        assertEquals("VIEWER", headers.getFirst("X-User-Roles"), "注入白名单内角色");
        assertEquals(GATEWAY_TOKEN, headers.getFirst("X-Gateway-Token"), "注入内部共享令牌");
        assertNull(headers.getFirst(HttpHeaders.AUTHORIZATION), "原始签名 token 不得泄露给下游（P2-1）");
    }

    @Test
    void 过期token_401且不调下游链() {
        String token = sign("1", 1L, "操作员", List.of("VIEWER"), past());
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/inventory/stock")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain);

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void algNone裸token_401且不调下游链() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/inventory/stock")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + NONE_ALG_TOKEN)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain);

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void roles含未知或逗号畸形值_仅保留白名单内角色() {
        String token = sign("1", 1L, "操作员", List.of("VIEWER", "UNKNOWN_ROLE", "ADMIN,EVIL"), future());
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/inventory/stock")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        filter.filter(exchange, chain);

        ServerWebExchange mutated = capturedMutatedExchange();
        assertEquals("VIEWER", mutated.getRequest().getHeaders().getFirst("X-User-Roles"),
                "未知角色与逗号畸形值应被丢弃（P2-4 白名单过滤）");
    }

    @Test
    void 白名单路径_跳过JWT校验但剥离伪造身份头放行() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health")
                .header("X-User-Id", "forged-999")
                .header("X-Gateway-Token", "forged-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        filter.filter(exchange, chain);

        ServerWebExchange mutated = capturedMutatedExchange();
        HttpHeaders headers = mutated.getRequest().getHeaders();
        assertNull(headers.getFirst("X-User-Id"), "白名单路径仍剥离伪造 X-User-Id（R2-P1-5）");
        assertNull(headers.getFirst("X-Gateway-Token"), "白名单路径仍剥离伪造 X-Gateway-Token（R2-P1-5）");
    }

    @Test
    void 无Authorization头_401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/inventory/stock").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain);

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, exchange.getResponse().getHeaders().getContentType(),
                "401 响应应带 JSON Content-Type（P2-5）");
    }

    @Test
    void 非Bearer前缀_401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/inventory/stock")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwdw==")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain);

        verify(chain, never()).filter(any());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    // ===== 工具方法 =====

    private ServerWebExchange capturedMutatedExchange() {
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        return captor.getValue();
    }

    /** 用与过滤器相同的 secret 真实签发 token（jjwt 0.12 builder API） */
    private static String sign(String subject, Object userId, String userName, Object roles, Date expiration) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("userId", userId)
                .claim("userName", userName)
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    private static Date future() {
        return Date.from(Instant.now().plus(1, ChronoUnit.HOURS));
    }

    private static Date past() {
        return Date.from(Instant.now().minus(1, ChronoUnit.MINUTES));
    }
}
