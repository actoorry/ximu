package com.by.ximu.inventory.module.batch;

import com.by.ximu.common.Auths;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.inventory.module.log.OperationLogService;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 批号 Controller。
 */
@RestController
@RequestMapping("/api/inventory/batch")
@RequiredArgsConstructor
public class BatchController {

    private final BatchService batchService;
    private final OperationLogService operationLogService;

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String productName,
                                            @RequestParam(required = false) String batchNo) {
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(batchService.page(pageQuery, productName, batchNo));
    }

    @GetMapping("/{id}")
    public Result<Batch> get(@PathVariable Long id) {
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(batchService.getById(id));
    }

    @PostMapping
    public Result<Batch> create(@Valid @RequestBody BatchCreateRequest req) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        // 白名单赋值：屏蔽 id/version/createdAt/updatedAt
        Batch entity = new Batch();
        entity.setBatchNo(req.getBatchNo());
        entity.setProductName(req.getProductName());
        entity.setCreateDate(req.getCreateDate());
        entity.setCreator(req.getCreator());
        entity.setRemark(req.getRemark());
        batchService.save(entity);
        operationLogService.record("batch", "CREATE", entity.getId(), entity.getBatchNo(), OperatorContext.getOperatorName(), req);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Batch entity) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        Batch existed = batchService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("批号不存在: " + id);
        }
        // 白名单：只允许改描述性字段，id/version/createdAt/updatedAt 不可经此修改
        existed.setBatchNo(entity.getBatchNo());
        existed.setProductName(entity.getProductName());
        existed.setCreateDate(entity.getCreateDate());
        existed.setCreator(entity.getCreator());
        existed.setRemark(entity.getRemark());
        try {
            if (!batchService.updateById(existed)) {
                throw new IllegalStateException("并发冲突，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("批号已存在: " + entity.getBatchNo());
        }
        operationLogService.record("batch", "UPDATE", id, existed.getBatchNo(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        Batch existed = batchService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("批号不存在: " + id);
        }
        batchService.removeById(id);
        operationLogService.record("batch", "DELETE", id, existed.getBatchNo(), OperatorContext.getOperatorName(), null);
        return Result.ok();
    }
}
