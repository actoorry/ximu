package com.by.ximu.safestock.module.safestock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.by.ximu.common.Auths;
import com.by.ximu.common.DimsNormalizer;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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

    @Transactional
    @PostMapping
    public Result<SafeStock> create(@Valid @RequestBody SafeStockCreateRequest req) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        // 幂等：requestId 非空时按「requestId + 当前操作人」查重（V8 复合唯一键兜底，P1-5）
        String requestId = req.getRequestId();
        if (StringUtils.hasText(requestId)) {
            SafeStock existed = findByIdempotent(requestId);
            if (existed != null) {
                return Result.ok(existed);
            }
        }
        // 白名单赋值：屏蔽 id/version/createdAt/updatedAt；品名/物料与库存五维同一套归一化（P1-4）
        SafeStock entity = new SafeStock();
        entity.setProductName(DimsNormalizer.normalize(req.getProductName()));
        entity.setMaterial(DimsNormalizer.normalize(req.getMaterial()));
        entity.setOrgId(req.getOrgId());
        entity.setServiceLevel(req.getServiceLevel());
        entity.setZValue(req.getZValue());
        entity.setReplenishCycle(req.getReplenishCycle());
        entity.setEconomicQty(req.getEconomicQty());
        entity.setOrderPointQty(req.getOrderPointQty());
        entity.setMaxQty(req.getMaxQty());
        entity.setSafeStock(req.getSafeStock());
        entity.setRequestId(requestId);
        entity.setCreatedBy(OperatorContext.getOperatorId());
        try {
            safeStockService.save(entity);
        } catch (DuplicateKeyException e) {
            // 并发同 requestId 双插：幂等返回已有；否则是 uk_safe_stock_dims 维度重复，明确报 400
            SafeStock existed = StringUtils.hasText(requestId) ? findByIdempotent(requestId) : null;
            if (existed != null) {
                return Result.ok(existed);
            }
            throw new IllegalArgumentException("该组织+品名+物料的安全库存配置已存在，请勿重复创建");
        }
        operationLogService.recordInTx("safe-stock", "CREATE", entity.getId(), entity.getProductName(), OperatorContext.getOperatorName(), req);
        return Result.ok(entity);
    }

    @Transactional
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SafeStock entity) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        SafeStock existed = safeStockService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("安全库存配置不存在: " + id);
        }
        // 白名单 + 部分更新（P1-5）：字段为 null 表示保持原值，避免漏传字段被静默清空；
        // id/version/createdAt/updatedAt 不可经此修改
        if (entity.getProductName() != null) {
            existed.setProductName(DimsNormalizer.normalize(entity.getProductName()));
        }
        if (entity.getMaterial() != null) {
            existed.setMaterial(DimsNormalizer.normalize(entity.getMaterial()));
        }
        if (entity.getOrgId() != null) {
            existed.setOrgId(entity.getOrgId());
        }
        if (entity.getServiceLevel() != null) {
            existed.setServiceLevel(entity.getServiceLevel());
        }
        if (entity.getZValue() != null) {
            existed.setZValue(entity.getZValue());
        }
        if (entity.getReplenishCycle() != null) {
            existed.setReplenishCycle(entity.getReplenishCycle());
        }
        if (entity.getEconomicQty() != null) {
            existed.setEconomicQty(entity.getEconomicQty());
        }
        if (entity.getOrderPointQty() != null) {
            existed.setOrderPointQty(entity.getOrderPointQty());
        }
        if (entity.getMaxQty() != null) {
            existed.setMaxQty(entity.getMaxQty());
        }
        if (entity.getSafeStock() != null) {
            existed.setSafeStock(entity.getSafeStock());
        }
        try {
            if (!safeStockService.updateById(existed)) {
                throw new IllegalStateException("并发冲突，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            // 改成已存在的维度组合时撞 uk_safe_stock_dims，转成明确的业务报错而非裸 500
            throw new IllegalArgumentException("该组织+品名+物料的安全库存配置已存在，无法改为此维度组合");
        }
        operationLogService.recordInTx("safe-stock", "UPDATE", id, existed.getProductName(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @Transactional
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        SafeStock existed = safeStockService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("安全库存配置不存在: " + id);
        }
        safeStockService.removeById(id);
        operationLogService.recordInTx("safe-stock", "DELETE", id, existed.getProductName(), OperatorContext.getOperatorName(), null);
        return Result.ok();
    }

    /** 幂等回查：requestId + 当前操作人（操作人缺失时退化为仅 requestId，与建单侧策略一致） */
    private SafeStock findByIdempotent(String requestId) {
        Long operatorId = OperatorContext.getOperatorId();
        return safeStockService.getOne(new LambdaQueryWrapper<SafeStock>()
                .eq(SafeStock::getRequestId, requestId)
                .eq(operatorId != null, SafeStock::getCreatedBy, operatorId), false);
    }
}
