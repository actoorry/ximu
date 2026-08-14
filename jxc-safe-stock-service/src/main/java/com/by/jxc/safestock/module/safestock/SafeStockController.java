package com.by.jxc.safestock.module.safestock;

import com.by.jxc.common.PageQuery;
import com.by.jxc.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 安全库存 Controller。
 */
@RestController
@RequestMapping("/api/safe-stock")
@RequiredArgsConstructor
public class SafeStockController {

    private final SafeStockService safeStockService;
    private final OperationLogService operationLogService;

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String productName,
                                            @RequestParam(required = false) String material) {
        return Result.ok(safeStockService.page(pageQuery, productName, material));
    }

    @GetMapping("/{id}")
    public Result<SafeStock> get(@PathVariable Long id) {
        return Result.ok(safeStockService.getById(id));
    }

    @PostMapping
    public Result<SafeStock> create(@RequestBody SafeStock entity) {
        safeStockService.save(entity);
        operationLogService.record("safe-stock", "CREATE", entity.getId(), entity.getProductName(), null, entity);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SafeStock entity) {
        entity.setId(id);
        safeStockService.updateById(entity);
        operationLogService.record("safe-stock", "UPDATE", id, entity.getProductName(), null, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SafeStock existed = safeStockService.getById(id);
        safeStockService.removeById(id);
        operationLogService.record("safe-stock", "DELETE", id, existed != null ? existed.getProductName() : null, null, null);
        return Result.ok();
    }
}
