package com.by.ximu.safestock;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 安全库存微服务启动类（端口 8082）。
 *
 * <p>负责安全库存参数（有货率/Z值/补货周期/订货点/最高库存/安全库存）的配置维护。
 * <p>注：本期仅提供配置的 CRUD，不做库存预警计算与补货建议（预警/补货能力已从范围砍掉，配置字段保留供后续或外部工具使用）。
 */
@SpringBootApplication(scanBasePackages = {"com.by.ximu.safestock", "com.by.ximu.common.web"})
@MapperScan({"com.by.ximu.safestock.module.safestock", "com.by.ximu.common.web.audit"})
public class SafeStockServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SafeStockServiceApplication.class, args);
    }
}
