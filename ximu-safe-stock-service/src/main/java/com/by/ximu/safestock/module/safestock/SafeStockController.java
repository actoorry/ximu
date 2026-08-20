package com.by.ximu.safestock.module.safestock;

import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.common.Role;
import com.by.ximu.common.web.security.RequireRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 安全库存 Controller。
 */
@RestController
@RequestMapping("/api/safe-stock")
@RequiredArgsConstructor
public class SafeStockController {

    private final SafeStockService safeStockService;

    @GetMapping
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String productName,
                                            @RequestParam(required = false) String material) {
        return Result.ok(safeStockService.page(pageQuery, productName, material));
    }

    @GetMapping("/{id}")
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<SafeStock> get(@PathVariable Long id) {
        SafeStock safeStock = safeStockService.getById(id);
        if (safeStock == null) {
            throw new NoSuchElementException("安全库存配置不存在: " + id);
        }
        return Result.ok(safeStock);
    }

    @PostMapping
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<SafeStock> create(@Valid @RequestBody SafeStockCreateRequest req) {
        return Result.ok(safeStockService.create(req));
    }

    @PutMapping("/{id}")
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<Void> update(@PathVariable Long id, @RequestBody SafeStock entity) {
        safeStockService.update(id, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<Void> delete(@PathVariable Long id,
                               @RequestBody(required = false) SafeStockDeleteRequest req) {
        safeStockService.delete(id, req != null ? req.getVersion() : null);
        return Result.ok();
    }
}
