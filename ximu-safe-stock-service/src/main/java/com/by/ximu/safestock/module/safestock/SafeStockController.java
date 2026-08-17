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
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(safeStockService.page(pageQuery, productName, material));
    }

    @GetMapping("/{id}")
    public Result<SafeStock> get(@PathVariable Long id) {
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(safeStockService.getById(id));
    }

    @PostMapping
    public Result<SafeStock> create(@Valid @RequestBody SafeStockCreateRequest req) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        // 白名单赋值：屏蔽 id/version/createdAt/updatedAt
        SafeStock entity = new SafeStock();
        entity.setProductName(req.getProductName());
        entity.setMaterial(req.getMaterial());
        entity.setOrgId(req.getOrgId());
        entity.setServiceLevel(req.getServiceLevel());
        entity.setZValue(req.getZValue());
        entity.setReplenishCycle(req.getReplenishCycle());
        entity.setEconomicQty(req.getEconomicQty());
        entity.setOrderPointQty(req.getOrderPointQty());
        entity.setMaxQty(req.getMaxQty());
        entity.setSafeStock(req.getSafeStock());
        safeStockService.save(entity);
        operationLogService.record("safe-stock", "CREATE", entity.getId(), entity.getProductName(), OperatorContext.getOperatorName(), req);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SafeStock entity) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        SafeStock existed = safeStockService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("安全库存配置不存在: " + id);
        }
        // 白名单：只允许改配置字段，id/version/createdAt/updatedAt 不可经此修改
        existed.setProductName(entity.getProductName());
        existed.setMaterial(entity.getMaterial());
        existed.setOrgId(entity.getOrgId());
        existed.setServiceLevel(entity.getServiceLevel());
        existed.setZValue(entity.getZValue());
        existed.setReplenishCycle(entity.getReplenishCycle());
        existed.setEconomicQty(entity.getEconomicQty());
        existed.setOrderPointQty(entity.getOrderPointQty());
        existed.setMaxQty(entity.getMaxQty());
        existed.setSafeStock(entity.getSafeStock());
        if (!safeStockService.updateById(existed)) {
            throw new IllegalStateException("并发冲突，请刷新后重试");
        }
        operationLogService.record("safe-stock", "UPDATE", id, existed.getProductName(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        SafeStock existed = safeStockService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("安全库存配置不存在: " + id);
        }
        safeStockService.removeById(id);
        operationLogService.record("safe-stock", "DELETE", id, existed.getProductName(), OperatorContext.getOperatorName(), null);
        return Result.ok();
    }
}
