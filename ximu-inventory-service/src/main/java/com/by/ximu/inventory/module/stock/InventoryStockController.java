package com.by.ximu.inventory.module.stock;

import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.inventory.module.log.OperationLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 库存统计 Controller。
 *
 * <p>列表查询时遍历分页结果回填库龄预警标记 warn：
 * {@code warn = stockAge != null && ageWarnDays != null && stockAge >= ageWarnDays}。
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
        fillWarn(result);
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<InventoryStock> get(@PathVariable Long id) {
        InventoryStock stock = inventoryStockService.getById(id);
        if (stock != null) {
            stock.setWarn(isWarn(stock));
        }
        return Result.ok(stock);
    }

    @PostMapping
    public Result<InventoryStock> create(@Valid @RequestBody InventoryStock entity) {
        inventoryStockService.save(entity);
        operationLogService.record("stock", "CREATE", entity.getId(), entity.getProductName(), null, entity);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody InventoryStock entity) {
        entity.setId(id);
        inventoryStockService.updateById(entity);
        operationLogService.record("stock", "UPDATE", id, entity.getProductName(), null, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        InventoryStock existed = inventoryStockService.getById(id);
        inventoryStockService.removeById(id);
        operationLogService.record("stock", "DELETE", id, existed != null ? existed.getProductName() : null, null, null);
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
        return s.getStockAge() != null && s.getAgeWarnDays() != null && s.getStockAge() >= s.getAgeWarnDays();
    }
}
