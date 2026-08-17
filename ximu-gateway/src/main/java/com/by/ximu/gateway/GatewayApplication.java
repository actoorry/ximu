package com.by.ximu.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一网关启动类（端口 8080）。
 *
 * <p>职责：校验 JWT → 路由到 inventory-service(8081)/safe-stock-service(8082) → 注入 X-User-* 身份头。
 * 下游服务只信任网关注入的身份头，本身不内置认证。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
