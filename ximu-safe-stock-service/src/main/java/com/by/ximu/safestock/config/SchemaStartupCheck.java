package com.by.ximu.safestock.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动期表结构校验（P1-5）：safe-stock 与 inventory 共用 {@code ximu} 库但不执行 Flyway 迁移，
 * 若 inventory 侧迁移未跑（{@code inventory_safe_stock} / {@code operation_log} 缺失），
 * 本服务要等首次读写才爆 500。启动时核对缺表直接失败（fail-fast），把问题前移到部署期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaStartupCheck implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME IN ('inventory_safe_stock', 'operation_log')",
                Integer.class);
        if (count == null || count < 2) {
            throw new IllegalStateException("数据库缺少 inventory_safe_stock / operation_log 表"
                    + "（预期 2 张，实际 " + (count == null ? "未知" : count) + " 张）："
                    + "请先启动 inventory-service 完成 Flyway 迁移，再启动本服务");
        }
        log.info("SchemaStartupCheck 通过：inventory_safe_stock / operation_log 表均已存在");
    }
}
