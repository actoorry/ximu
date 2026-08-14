package com.by.ximu.inventory.module.batch;

import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.inventory.module.log.OperationLogService;
import jakarta.validation.Valid;
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
        return Result.ok(batchService.page(pageQuery, productName, batchNo));
    }

    @GetMapping("/{id}")
    public Result<Batch> get(@PathVariable Long id) {
        return Result.ok(batchService.getById(id));
    }

    @PostMapping
    public Result<Batch> create(@Valid @RequestBody Batch entity) {
        batchService.save(entity);
        operationLogService.record("batch", "CREATE", entity.getId(), entity.getBatchNo(), null, entity);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Batch entity) {
        entity.setId(id);
        batchService.updateById(entity);
        operationLogService.record("batch", "UPDATE", id, entity.getBatchNo(), null, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Batch existed = batchService.getById(id);
        batchService.removeById(id);
        operationLogService.record("batch", "DELETE", id, existed != null ? existed.getBatchNo() : null, null, null);
        return Result.ok();
    }
}
