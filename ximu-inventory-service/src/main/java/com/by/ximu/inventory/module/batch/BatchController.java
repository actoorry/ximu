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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 批号 Controller。
 *
 * <p>写操作与审计日志同事务（P2-7：{@code recordInTx}，审计失败即回滚业务写，
 * 基础数据变更不再出现"改了数据、丢审计"的静默不一致）。
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

    @Transactional
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
        operationLogService.recordInTx("batch", "CREATE", entity.getId(), entity.getBatchNo(), OperatorContext.getOperatorName(), req);
        return Result.ok(entity);
    }

    @Transactional
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
        operationLogService.recordInTx("batch", "UPDATE", id, existed.getBatchNo(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @Transactional
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Auths.requireRole(Role.CHECKER, Role.ADMIN);
        Batch existed = batchService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("批号不存在: " + id);
        }
        batchService.removeById(id);
        operationLogService.recordInTx("batch", "DELETE", id, existed.getBatchNo(), OperatorContext.getOperatorName(), null);
        return Result.ok();
    }
}
