package com.by.ximu.inventory.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link OperatorContextFilter} P0-5 路径判定回归：
 * ① {@code /api;x/...} 形态的路径参数不可绕过身份校验（判定用 getServletPath 而非 getRequestURI）；
 * ② 白名单外非 /api/ 路径直接 404；③ 白名单内路径（actuator health/info）正常放行。
 * safe-stock 服务的同款 Filter 为复制实现，行为以本类为代表锁定。
 */
class OperatorContextFilterTest {

    private OperatorContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OperatorContextFilter();
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "gatewayToken", "");
        ReflectionTestUtils.setField(filter, "publicPaths", "/actuator/health,/actuator/info");
    }

    @Test
    void 分号路径参数_不绕过身份校验() throws Exception {
        // 模拟容器行为：getServletPath 已剥离 ;x 并解码（与路由判定一致），getRequestURI 保留原始 ;x
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api;x/inventory/stock");
        request.setRequestURI("/api;x/inventory/stock");
        request.setServletPath("/api/inventory/stock");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "带 ; 路径参数的 /api 请求不得放行");
        assertEquals(401, response.getStatus(), "缺身份头应 401 而非穿透到 Controller");
    }

    @Test
    void 白名单外非api路径_404() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        request.setServletPath("/v3/api-docs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "白名单外路径不得放行");
        assertEquals(404, response.getStatus());
    }

    @Test
    void 白名单内路径_放行() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "actuator 健康检查应放行");
        assertEquals(200, response.getStatus());
    }
}
