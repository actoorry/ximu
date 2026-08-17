package com.by.ximu.safestock.config;

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
 * <p>文档入口：Swagger UI {@code /swagger-ui.html}、OpenAPI JSON {@code /v3/api-docs}。
 * <p>身份头：信任网关注入的 X-User-Id / X-User-Name / X-User-Roles，声明为 API 级安全头供 Swagger UI 填写。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "operator-identity";

    @Bean
    public OpenAPI safeStockOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ximu 安全库存服务 API")
                        .description("安全库存参数配置维护（有货率、Z 值、补货周期、经济补货量、订货点、最大库存、安全库存）。本期仅配置 CRUD，不做预警计算。"

                                + "鉴权说明：直接访问需携带网关注入的身份头（见 Authorize），或经网关（8080）。")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-User-Roles")
                                .description("身份头契约（网关注入）：X-User-Id / X-User-Name / X-User-Roles（逗号分隔角色）。")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
