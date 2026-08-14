package com.by.jxc.inventory.module.check;

import com.by.jxc.common.PageQuery;
import com.by.jxc.common.Result;
import com.by.jxc.inventory.module.log.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 盘点 Controller。
 *
 * <p>create 接收「头 + items」请求体，返回 DetailVO（头 + items + totalQty）；
 * get/list 同样返回头 + items/totalQty。
 * <p>流转字段集合：{@code id / status / action / operator}；
 * PUT /{id} 当 body 含 action 时走状态机流转（approve/check），否则走普通编辑。
 */
@RestController
@RequestMapping("/api/inventory/check")
@RequiredArgsConstructor
public class InventoryCheckController {

    private final InventoryCheckService inventoryCheckService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    private static final Set<String> TRANSITION_KEYS = Set.of("id", "status", "action", "operator");

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String batchNo) {
        return Result.ok(inventoryCheckService.page(pageQuery, status, batchNo));
    }

    @GetMapping("/{id}")
    public Result<CheckDetailVO> get(@PathVariable Long id) {
        return Result.ok(inventoryCheckService.getDetail(id));
    }

    @PostMapping
    public Result<CheckDetailVO> create(@Valid @RequestBody CheckCreateRequest req) {
        CheckDetailVO vo = inventoryCheckService.create(req);
        operationLogService.record("check", "CREATE", vo.getId(), vo.getCheckNo(), null, req);
        return Result.ok(vo);
    }

    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (isTransition(body)) {
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> {
                    inventoryCheckService.approve(id);
                    operationLogService.record("check", "APPROVE", id, null, operator(body), null);
                }
                case "check" -> {
                    inventoryCheckService.check(id);
                    operationLogService.record("check", "CHECK", id, null, operator(body), null);
                }
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        InventoryCheck entity = objectMapper.convertValue(body, InventoryCheck.class);
        entity.setId(id);
        entity.setStatus(null); // 防越权
        inventoryCheckService.updateById(entity);
        operationLogService.record("check", "UPDATE", id, entity.getCheckNo(), null, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        InventoryCheck existed = inventoryCheckService.getById(id);
        inventoryCheckService.deleteWithItems(id);
        operationLogService.record("check", "DELETE", id, existed != null ? existed.getCheckNo() : null, null, null);
        return Result.ok();
    }

    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }

    private String operator(Map<String, Object> body) {
        return body.containsKey("operator") ? String.valueOf(body.get("operator")) : null;
    }
}
