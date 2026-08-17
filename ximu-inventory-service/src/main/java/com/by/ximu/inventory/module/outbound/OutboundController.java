package com.by.ximu.inventory.module.outbound;

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
 * 出库 Controller（薄层：角色校验、状态机、库存联动、审计日志均在 Service 事务内完成）。
 *
 * <p>create 接收「头 + items」请求体，返回 DetailVO（头 + items + totalQty）；
 * get/list 同样返回头 + items/totalQty。
 * <p>流转字段集合：{@code id / status / action / operator}；
 * PUT /{id} 当 body 含 action 时走状态机流转（approve），否则走普通编辑（白名单 DTO 绑定）。
 */
@RestController
@RequestMapping("/api/inventory/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;
    private final ObjectMapper objectMapper;

    /** 出库单据的流转字段集合 */
    private static final Set<String> TRANSITION_KEYS = Set.of("id", "status", "action", "operator");

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String status) {
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(outboundService.page(pageQuery, status));
    }

    @GetMapping("/{id}")
    public Result<OutboundDetailVO> get(@PathVariable Long id) {
        Auths.requireRole(Role.VIEWER, Role.ADMIN);
        return Result.ok(outboundService.getDetail(id));
    }

    @PostMapping
    public Result<OutboundDetailVO> create(@Valid @RequestBody OutboundCreateRequest req) {
        return Result.ok(outboundService.create(req));
    }

    /**
     * 编辑 / 流转出库单。
     *
     * <p>body 含 {@code action} 字段 → 流转（approve）；否则普通编辑。
     * <p>普通编辑绑定 {@link OutboundUpdateRequest} 白名单 DTO，
     * {@code id/status/version/createdBy/时间戳} 等字段即使传入也会被忽略。
     */
    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (isTransition(body)) {
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> outboundService.approve(id);
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        outboundService.updateHead(id, objectMapper.convertValue(body, OutboundUpdateRequest.class));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        outboundService.deleteWithItems(id);
        return Result.ok();
    }

    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }
}
