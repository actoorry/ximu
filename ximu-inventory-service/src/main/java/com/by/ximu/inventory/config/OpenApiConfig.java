package com.by.ximu.inventory.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI 配置。
 *
 * <p>文档入口（默认由 springdoc-openapi 提供）：
 * <ul>
 *   <li>Swagger UI：{@code /swagger-ui.html}</li>
 *   <li>OpenAPI JSON：{@code /v3/api-docs}</li>
 * </ul>
 * <p>身份头：本服务不内置认证，信任网关注入的 X-User-Id / X-User-Name / X-User-Roles 三个头；
 * 此处把它们声明为 API 级安全头，Swagger UI 顶部提供「Authorize」按钮可直接填写测试身份。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "operator-identity";

    @Bean
    public OpenAPI inventoryOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ximu 库存服务 API")
                        .description("入库 / 出库 / 盘点 / 调拨 / 实时库存 / 批号 / 操作日志。"

                                + "鉴权说明：直接访问本服务时，需携带网关注入的身份头（见 Authorize），"

                                + "或经网关（8080）由 JWT 统一注入。")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-User-Roles")
                                .description("身份头契约（网关注入）：X-User-Id=用户ID、X-User-Name=用户名、X-User-Roles=逗号分隔角色（ADMIN/CREATOR/APPROVER/CHECKER/VIEWER）。"

                                        + "Swagger 的 Authorize 按钮只填一个值，建议此处填 X-User-Roles，"

                                        + "X-User-Id / X-User-Name 用 Try it out 的 Header 参数补充。")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
