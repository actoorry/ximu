package com.by.ximu.inventory.module.transfer;

import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.inventory.module.log.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 调拨 Controller。
 *
 * <p>create 接收「头 + items」请求体，返回 DetailVO（头 + items + totalQty）；
 * get/list 同样返回头 + items/totalQty。
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
                                            @RequestParam(required = false) String batchNo) {
        return Result.ok(transferService.page(pageQuery, status, batchNo));
    }

    @GetMapping("/{id}")
    public Result<TransferDetailVO> get(@PathVariable Long id) {
        return Result.ok(transferService.getDetail(id));
    }

    @PostMapping
    public Result<TransferDetailVO> create(@Valid @RequestBody TransferCreateRequest req) {
        TransferDetailVO vo = transferService.create(req);
        operationLogService.record("transfer", "CREATE", vo.getId(), vo.getTransferNo(), OperatorContext.getOperatorName(), req);
        return Result.ok(vo);
    }

    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (isTransition(body)) {
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> {
                    transferService.approve(id);
                    operationLogService.record("transfer", "APPROVE", id, null, OperatorContext.getOperatorName(), null);
                }
                case "complete" -> {
                    transferService.complete(id);
                    operationLogService.record("transfer", "COMPLETE", id, null, OperatorContext.getOperatorName(), null);
                }
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        Transfer entity = objectMapper.convertValue(body, Transfer.class);
        entity.setId(id);
        entity.setStatus(null); // 防越权
        transferService.updateById(entity);
        operationLogService.record("transfer", "UPDATE", id, entity.getTransferNo(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Transfer existed = transferService.getById(id);
        transferService.deleteWithItems(id);
        operationLogService.record("transfer", "DELETE", id, existed != null ? existed.getTransferNo() : null, OperatorContext.getOperatorName(), null);
        return Result.ok();
    }

    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }


}
