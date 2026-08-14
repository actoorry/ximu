package com.by.jxc.safestock;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 安全库存微服务启动类（端口 8082）。
 *
 * <p>不碰库存流水，只读库存做预警；负责安全库存配置 / 阈值管理 / 补货策略。
 */
@SpringBootApplication
@MapperScan("com.by.jxc.safestock.module.safestock")
public class SafeStockServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafeStockServiceApplication.class, args);
    }
}
