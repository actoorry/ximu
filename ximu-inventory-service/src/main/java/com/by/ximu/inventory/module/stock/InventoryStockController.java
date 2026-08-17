package com.by.ximu.inventory.module.stock;

import com.by.ximu.common.Auths;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.inventory.module.log.OperationLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 库存统计 Controller。
 *
 * <p>列表查询时先回填库龄天数 stockAgeDays，再回填库龄预警标记 warn（动态优先、静态回退）：
 * {@code warn = stockAgeDays != null ? stockAgeDays >= ageWarnDays : (stockAge != null && stockAge >= ageWarnDays)}，
 * ageWarnDays 为 null 时为 false。
 */
@RestController
@RequestMapping("/api/inventory/stock")
@RequiredArgsConstructor
public class InventoryStockController {

    private final InventoryStockService inventoryStockService;
    private final OperationLogService operationLogService;

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String productName,
                                            @RequestParam(required = false) String grade) {
        Map<String, Object> result = inventoryStockService.page(pageQuery, productName, grade);
        fillStockAgeDays(result);
        fillWarn(result);
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<InventoryStock> get(@PathVariable Long id) {
        InventoryStock stock = inventoryStockService.getById(id);
        if (stock != null) {
            fillStockAgeDays(stock);
            stock.setWarn(isWarn(stock));
        }
        return Result.ok(stock);
    }

    @PostMapping
    public Result<InventoryStock> create(@Valid @RequestBody InventoryStock entity) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        inventoryStockService.save(entity);
        operationLogService.record("stock", "CREATE", entity.getId(), entity.getProductName(), OperatorContext.getOperatorName(), entity);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody InventoryStock entity) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        entity.setId(id);
        inventoryStockService.updateById(entity);
        operationLogService.record("stock", "UPDATE", id, entity.getProductName(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        InventoryStock existed = inventoryStockService.getById(id);
        inventoryStockService.removeById(id);
        operationLogService.record("stock", "DELETE", id, existed != null ? existed.getProductName() : null, OperatorContext.getOperatorName(), null);
        return Result.ok();
    }

    /** 遍历列表回填 warn 字段 */
    @SuppressWarnings("unchecked")
    private void fillWarn(Map<String, Object> result) {
        Object listObj = result.get("list");
        if (listObj instanceof List<?>) {
            for (Object item : (List<?>) listObj) {
                if (item instanceof InventoryStock s) {
                    s.setWarn(isWarn(s));
                }
            }
        }
    }

    private boolean isWarn(InventoryStock s) {
        return InventoryStock.isWarn(s.getStockAgeDays(), s.getStockAge(), s.getAgeWarnDays());
    }

    /** 遍历列表回填库龄天数 stockAgeDays */
    @SuppressWarnings("unchecked")
    private void fillStockAgeDays(Map<String, Object> result) {
        Object listObj = result.get("list");
        if (listObj instanceof List<?>) {
            for (Object item : (List<?>) listObj) {
                if (item instanceof InventoryStock s) {
                    fillStockAgeDays(s);
                }
            }
        }
    }

    /** 单行回填库龄天数（now - firstInboundAt 向下取整，firstInboundAt 为 null 时为 null） */
    private void fillStockAgeDays(InventoryStock s) {
        s.setStockAgeDays(InventoryStock.stockAgeDays(s.getFirstInboundAt(), LocalDateTime.now()));
    }
}
