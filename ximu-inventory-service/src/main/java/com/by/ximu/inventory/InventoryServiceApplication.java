package com.by.ximu.inventory;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 库存操作微服务启动类（端口 8081）。
 *
 * <p>操作同一个库存账本，强一致性；包含入库/出库/盘点/调拨/库存统计/批号/操作日志 7 个子模块。
 */
@SpringBootApplication(scanBasePackages = {"com.by.ximu.inventory", "com.by.ximu.common.web"})
@MapperScan({
        "com.by.ximu.inventory.module.inbound",
        "com.by.ximu.inventory.module.outbound",
        "com.by.ximu.inventory.module.check",
        "com.by.ximu.inventory.module.transfer",
        "com.by.ximu.inventory.module.stock",
        "com.by.ximu.inventory.module.batch",
        "com.by.ximu.inventory.module.log",
        "com.by.ximu.common.web.audit"
})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
