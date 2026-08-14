package com.by.jxc.inventory.module.transfer;

import com.by.jxc.common.PageQuery;
import com.by.jxc.common.Result;
import com.by.jxc.inventory.module.log.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 调拨 Controller。
 *
 * <p>流转字段集合：{@code id / status / action / operator}；
 * PUT /{id} 当 body 含 action 时走状态机流转（approve/complete），否则走普通编辑。
 */
@RestController
@RequestMapping("/api/inventory/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    private static final Set<String> TRANSITION_KEYS = Set.of("id", "status", "action", "operator");

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String targetLocation) {
        return Result.ok(transferService.page(pageQuery, status, targetLocation));
    }

    @GetMapping("/{id}")
    public Result<Transfer> get(@PathVariable Long id) {
        return Result.ok(transferService.getById(id));
    }

    @PostMapping
    public Result<Transfer> create(@RequestBody Transfer entity) {
        entity.setStatus("CREATED"); // 强制初始状态，防越权
        transferService.save(entity);
        operationLogService.record("transfer", "CREATE", entity.getId(), entity.getTransferNo(), null, entity);
        return Result.ok(entity);
    }

    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (isTransition(body)) {
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> {
                    transferService.approve(id);
                    operationLogService.record("transfer", "APPROVE", id, null, operator(body), null);
                }
                case "complete" -> {
                    transferService.complete(id);
                    operationLogService.record("transfer", "COMPLETE", id, null, operator(body), null);
                }
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        Transfer entity = objectMapper.convertValue(body, Transfer.class);
        entity.setId(id);
        entity.setStatus(null); // 防越权
        transferService.updateById(entity);
        operationLogService.record("transfer", "UPDATE", id, entity.getTransferNo(), null, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Transfer existed = transferService.getById(id);
        transferService.removeById(id);
        operationLogService.record("transfer", "DELETE", id, existed != null ? existed.getTransferNo() : null, null, null);
        return Result.ok();
    }

    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }

    private String operator(Map<String, Object> body) {
        return body.containsKey("operator") ? String.valueOf(body.get("operator")) : null;
    }
}
