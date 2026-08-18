package com.by.ximu.safestock.config;

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
import java.util.Arrays;
import java.util.List;

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

    /** 内部共享令牌：非空时要求请求携带匹配的 X-Gateway-Token（证明请求经网关，防直连伪造身份） */
    @Value("${app.gateway-token:}")
    private String gatewayToken;

    /** 公开路径白名单（逗号分隔前缀）：/api/ 之外仅这些路径可访问，其余一律 404 */
    @Value("${app.auth.public-paths:/actuator/health,/actuator/info}")
    private String publicPaths;

    /**
     * 启动告警（P1 残留点）：令牌留空时网关令牌校验整体跳过（fail-open），
     * 任何能直连本服务的请求都可伪造 X-User-* 头——配置遗漏必须在启动日志里显式暴露，
     * 不能零告警静默降级。开发环境（dev profile 已配共享令牌）不应出现本告警。
     */
    @PostConstruct
    void warnIfGatewayTokenMissing() {
        if (gatewayToken == null || gatewayToken.isBlank()) {
            log.warn("app.gateway-token 未配置：X-Gateway-Token 校验将被跳过，直连本服务可伪造身份头；生产环境必须配置该令牌");
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
                    if (gatewayToken != null && !gatewayToken.isBlank()) {
                        String token = request.getHeader("X-Gateway-Token");
                        if (token == null || !gatewayToken.equals(token)) {
                            writeUnauthorized(response);
                            return;
                        }
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
                    String rolesHeader = request.getHeader("X-User-Roles");
                    List<String> roles = (rolesHeader == null || rolesHeader.isBlank())
                            ? List.of() : Arrays.asList(rolesHeader.split(","));
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
