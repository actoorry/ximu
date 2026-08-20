package com.by.ximu.inventory.module.inbound;

import com.by.ximu.common.DocStatus;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.common.Role;
import com.by.ximu.common.web.security.RequireRole;
import com.by.ximu.inventory.common.TransitionSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 入库 Controller（薄层：角色校验、状态机、库存联动、审计日志均在 Service 事务内完成）。
 *
 * <p>create 接收「头 + items」请求体，返回 DetailVO（头 + items + totalQty）；
 * get/list 同样返回头 + items/totalQty。
 * <p>流转字段集合：{@code id / status / action / auditLevel / checker / operator}；
 * PUT /{id} 当 body 含 action 时走状态机流转，否则走普通编辑（白名单 DTO 绑定）。
 */
@RestController
@RequestMapping("/api/inventory/inbound")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService inboundService;
    private final ObjectMapper objectMapper;

    /** 入库单据的流转字段集合 */
    private static final Set<String> TRANSITION_KEYS = Set.of("id", "status", "action", "auditLevel", "checker", "operator");

    @GetMapping
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String inboundType) {
        return Result.ok(inboundService.page(pageQuery, status, inboundType));
    }

    @GetMapping("/{id}")
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<InboundDetailVO> get(@PathVariable Long id) {
        InboundDetailVO vo = inboundService.getDetail(id);
        if (vo == null) {
            throw new NoSuchElementException("入库单不存在: " + id);
        }
        return Result.ok(vo);
    }

    @PostMapping
    @RequireRole({Role.CREATOR, Role.ADMIN})
    public Result<InboundDetailVO> create(@Valid @RequestBody InboundCreateRequest req) {
        return Result.ok(inboundService.create(req));
    }

    /**
     * 编辑 / 流转入库单。
     *
     * <p>body 含 {@code action} 字段 → 流转（approve/check）；否则普通编辑。
     * <p>流转不信任请求体身份字段：审核人一律取 OperatorContext 的可信登录人，
     * {@code checker/auditLevel} 仅保留在 TRANSITION_KEYS 中用于识别旧前端请求形态，值被忽略；
     * 携带 {@code status} 时必须与 action 目标状态一致，否则 400（P2-16，不再静默忽略）；
     * auditLevel 保持制单人建单时指定的级别，流转不再覆盖。
     * <p>普通编辑绑定 {@link InboundUpdateRequest} 白名单 DTO，
     * {@code id/status/version/createdBy/时间戳} 等字段即使传入也会被忽略。
     */
    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (TransitionSupport.isTransition(body)) {
            // R2-P1-3：流转请求体仅允许 TRANSITION_KEYS 内字段，多余字段显式 400（不再静默降级为普通编辑）
            TransitionSupport.requireTransitionBody(body, TRANSITION_KEYS);
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> { requireStatusMatch(body, "approve", DocStatus.APPROVED.name()); inboundService.approve(id); }
                case "check" -> { requireStatusMatch(body, "check", DocStatus.CHECKED.name()); inboundService.check(id); }
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        // 普通编辑：剥离 action 后绑定白名单 DTO（防御性剥离，body 已保证不含 action）
        Map<String, Object> editBody = new HashMap<>(body);
        editBody.remove("action");
        inboundService.updateHead(id, objectMapper.convertValue(editBody, InboundUpdateRequest.class));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        inboundService.deleteWithItems(id);
        return Result.ok();
    }

    /** 流转请求携带 status 时必须与 action 目标状态一致，否则 400（P2-16：不再静默忽略，防前端误以为已生效） */
    private static void requireStatusMatch(Map<String, Object> body, String action, String targetStatus) {
        if (body.containsKey("status") && !targetStatus.equals(body.get("status"))) {
            throw new IllegalArgumentException("status 与 action 不一致：action=" + action + " 的目标状态为 " + targetStatus);
        }
    }
}
