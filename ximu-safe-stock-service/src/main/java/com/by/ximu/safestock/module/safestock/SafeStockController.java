package com.by.ximu.safestock.module.safestock;

import com.by.ximu.common.Auths;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import jakarta.validation.Valid;
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
    public Result<SafeStock> create(@Valid @RequestBody SafeStock entity) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        safeStockService.save(entity);
        operationLogService.record("safe-stock", "CREATE", entity.getId(), entity.getProductName(), OperatorContext.getOperatorName(), entity);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SafeStock entity) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        entity.setId(id);
        safeStockService.updateById(entity);
        operationLogService.record("safe-stock", "UPDATE", id, entity.getProductName(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        SafeStock existed = safeStockService.getById(id);
        safeStockService.removeById(id);
        operationLogService.record("safe-stock", "DELETE", id, existed != null ? existed.getProductName() : null, OperatorContext.getOperatorName(), null);
        return Result.ok();
    }
}
