package com.by.ximu.safestock.config;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
@Component
public class OperatorContextFilter extends OncePerRequestFilter {

    @Value("${app.auth.enabled:true}")
    private boolean authEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            if (request.getRequestURI().startsWith("/api/")) {
                if (authEnabled) {
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
            }
            filterChain.doFilter(request, response);
        } finally {
            OperatorContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"未认证或缺少身份信息\",\"data\":null}");
    }
}
