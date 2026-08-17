package com.by.ximu.inventory.module.transfer;

import com.by.ximu.common.Auths;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.common.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 调拨 Controller（薄层：角色校验、状态机、库存联动、审计日志均在 Service 事务内完成）。
 *
 * <p>create 接收「头 + items」请求体，返回 DetailVO（头 + items + totalQty）；
 * get/list 同样返回头 + items/totalQty。
 * <p>流转字段集合：{@code id / status / action / operator}；
 * PUT /{id} 当 body 含 action 时走状态机流转（approve/complete），否则走普通编辑（白名单 DTO 绑定）。
 */
@RestController
@RequestMapping("/api/inventory/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    private static final Set<String> TRANSITION_KEYS = Set.of("id", "status", "action", "operator");

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String batchNo) {
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(transferService.page(pageQuery, status, batchNo));
    }

    @GetMapping("/{id}")
    public Result<TransferDetailVO> get(@PathVariable Long id) {
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(transferService.getDetail(id));
    }

    @PostMapping
    public Result<TransferDetailVO> create(@Valid @RequestBody TransferCreateRequest req) {
        return Result.ok(transferService.create(req));
    }

    /**
     * 编辑 / 流转调拨单。
     *
     * <p>body 含 {@code action} 字段 → 流转（approve/complete）；否则普通编辑。
     * <p>普通编辑绑定 {@link TransferUpdateRequest} 白名单 DTO，
     * {@code id/status/version/createdBy/时间戳} 等字段即使传入也会被忽略。
     */
    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (isTransition(body)) {
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> transferService.approve(id);
                case "complete" -> transferService.complete(id);
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        transferService.updateHead(id, objectMapper.convertValue(body, TransferUpdateRequest.class));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        transferService.deleteWithItems(id);
        return Result.ok();
    }

    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }
}
