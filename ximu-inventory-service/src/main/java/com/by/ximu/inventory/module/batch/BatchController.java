package com.by.ximu.inventory.module.batch;

import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.common.web.audit.OperationLogService;
import com.by.ximu.common.web.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

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
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String productName,
                                            @RequestParam(required = false) String batchNo) {
        return Result.ok(batchService.page(pageQuery, productName, batchNo));
    }

    @GetMapping("/{id}")
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<Batch> get(@PathVariable Long id) {
        Batch batch = batchService.getById(id);
        if (batch == null) {
            throw new NoSuchElementException("批号不存在: " + id);
        }
        return Result.ok(batch);
    }

    @Transactional
    @PostMapping
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<Batch> create(@Valid @RequestBody BatchCreateRequest req) {
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
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BatchUpdateRequest req) {
        Batch existed = batchService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("批号不存在: " + id);
        }
        // R2-P2-15：白名单 DTO + 部分更新语义——null/空白串保持原值（hasText 判定），
        // 不再全量覆盖：传 {"id":5} 不会清空其余字段，batchNo 也不会被空串清掉致唯一索引失效。
        if (StringUtils.hasText(req.getBatchNo())) {
            existed.setBatchNo(req.getBatchNo());
        }
        if (StringUtils.hasText(req.getProductName())) {
            existed.setProductName(req.getProductName());
        }
        if (req.getCreateDate() != null) {
            existed.setCreateDate(req.getCreateDate());
        }
        if (StringUtils.hasText(req.getCreator())) {
            existed.setCreator(req.getCreator());
        }
        if (StringUtils.hasText(req.getRemark())) {
            existed.setRemark(req.getRemark());
        }
        try {
            if (!batchService.updateById(existed)) {
                throw new IllegalStateException("并发冲突，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("批号已存在: " + existed.getBatchNo());
        }
        // R2-P2-12：审计记录更新后的生效值（existed）而非请求体，detail 可还原字段实际变化
        operationLogService.recordInTx("batch", "UPDATE", id, existed.getBatchNo(), OperatorContext.getOperatorName(), existed);
        return Result.ok();
    }

    @Transactional
    @DeleteMapping("/{id}")
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<Void> delete(@PathVariable Long id) {
        Batch existed = batchService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("批号不存在: " + id);
        }
        batchService.removeById(id);
        operationLogService.recordInTx("batch", "DELETE", id, existed.getBatchNo(), OperatorContext.getOperatorName(), null);
        return Result.ok();
    }
}
