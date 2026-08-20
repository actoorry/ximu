package com.by.ximu.common.web.config;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 身份上下文过滤器：从网关注入的 X-User-* 头解析操作人，写入 OperatorContext，请求结束清理。
 *
 * <p>app.auth.enabled=true（默认/生产）时，/api/** 缺少身份头直接 401；
 * 设为 false（仅本地开发）时放行并以 dev 身份兜底。
 *
 * <p>路径判定（P0-5）：使用 {@link HttpServletRequest#getServletPath()}——容器返回的
 * 已去除路径参数（{@code ;x} 段）且已解码的路径，与 DispatcherServlet 路由判定一致；
 * 不得使用 {@code getRequestURI()}（原始串含 {@code ;} 段，{@code /api;x/...} 可绕过
 * {@code startsWith("/api/")} 判定而路由仍命中 Controller）。
 * 白名单（app.auth.public-paths，前缀匹配）外的非 /api/ 路径一律 404（纵深防御：
 * 不存在任何未经过本过滤器校验的可路由路径）。
 */
@Slf4j
@Component
public class OperatorContextFilter extends OncePerRequestFilter {

    @Value("${app.auth.enabled:true}")
    private boolean authEnabled;

    /** 内部共享令牌：auth.enabled=true 时恒要求请求携带匹配的 X-Gateway-Token（缺失拒绝启动，防直连伪造身份） */
    @Value("${app.gateway-token:}")
    private String gatewayToken;

    /** 公开路径白名单（逗号分隔前缀）：/api/ 之外仅这些路径可访问，其余一律 404 */
    @Value("${app.auth.public-paths:/actuator/health,/actuator/info}")
    private String publicPaths;

    /**
     * 启动期 fail-fast（R2-P1-4）：auth.enabled=true 时网关令牌必填，缺失/留空直接拒绝启动。
     * 原实现仅 WARN 且运行时校验整体跳过（fail-open），运维按旧注释「GATEWAY_TOKEN= 空串关闭校验」
     * 配置后会静默放行所有直连伪造身份请求；现改为与网关 GatewaySecretChecker 一致的 fail-fast 哲学。
     * dev profile 有兜底令牌（dev-shared-gateway-token），不受影响。
     */
    @PostConstruct
    void failFastIfGatewayTokenMissing() {
        if (authEnabled && (gatewayToken == null || gatewayToken.isBlank())) {
            throw new IllegalStateException(
                    "app.gateway-token 未配置：auth.enabled=true 时网关令牌必填（缺失则无法证明请求经网关，"
                    + "直连本服务可伪造 X-User-* 身份头），拒绝启动");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // getServletPath 已去除 ;x 路径参数并解码，与路由判定一致（P0-5，防 /api;x 绕过）
            String path = request.getServletPath();
            if (path.startsWith("/api/")) {
                if (authEnabled) {
                    // R2-P1-4：auth.enabled=true 时恒要求 X-Gateway-Token 匹配（令牌必填已在启动期 fail-fast，
                    // 不存在「留空跳过校验」的逃生门）；恒定时间比较（P2-6）防时序侧信道
                    String token = request.getHeader("X-Gateway-Token");
                    if (token == null || gatewayToken == null || gatewayToken.isBlank()
                            || !MessageDigest.isEqual(
                                    gatewayToken.getBytes(StandardCharsets.UTF_8),
                                    token.getBytes(StandardCharsets.UTF_8))) {
                        writeUnauthorized(response);
                        return;
                    }
                    String userId = request.getHeader("X-User-Id");
                    String userName = request.getHeader("X-User-Name");
                    if (userId == null || userId.isBlank()) {
                        writeUnauthorized(response);
                        return;
                    }
                    Long uid;
                    try {
                        uid = Long.valueOf(userId);
                    } catch (NumberFormatException e) {
                        writeUnauthorized(response);
                        return;
                    }
                    // P2-21：逐项 trim 后过滤空段，防「ADMIN, VIEWER」中 " VIEWER" 因前导空格被 403 误拒
                    String rolesHeader = request.getHeader("X-User-Roles");
                    List<String> roles = (rolesHeader == null || rolesHeader.isBlank())
                            ? List.of()
                            : Arrays.stream(rolesHeader.split(","))
                                    .map(String::trim)
                                    .filter(role -> !role.isEmpty())
                                    .collect(Collectors.toList());
                    OperatorContext.set(new Operator(uid, userName, roles));
                } else {
                    OperatorContext.set(new Operator(0L, "dev", List.of("ADMIN")));
                }
            } else if (!isPublicPath(path)) {
                // 非 /api/ 且不在白名单（actuator health/info 等）：直接 404，不留任何未校验的可路由路径
                writeNotFound(response);
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            OperatorContext.clear();
        }
    }

    /** 白名单前缀匹配（逗号分隔配置，忽略首尾空格） */
    private boolean isPublicPath(String path) {
        for (String prefix : publicPaths.split(",")) {
            String p = prefix.trim();
            if (!p.isEmpty() && path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"未认证或缺少身份信息\",\"data\":null}");
    }

    private void writeNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":404,\"message\":\"资源不存在\",\"data\":null}");
    }
}
