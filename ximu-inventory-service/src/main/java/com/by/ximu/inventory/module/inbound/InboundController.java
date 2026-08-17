package com.by.ximu.inventory.module.inbound;

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
 * 入库 Controller。
 *
 * <p>create 接收「头 + items」请求体，返回 DetailVO（头 + items + totalQty）；
 * get/list 同样返回头 + items/totalQty。
 * <p>流转字段集合：{@code id / status / action / auditLevel / checker / operator}；
 * PUT /{id} 当 body 含 action 时走状态机流转，否则走普通编辑。
 */
@RestController
@RequestMapping("/api/inventory/inbound")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService inboundService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    /** 入库单据的流转字段集合 */
    private static final Set<String> TRANSITION_KEYS = Set.of("id", "status", "action", "auditLevel", "checker", "operator");

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String inboundType) {
        return Result.ok(inboundService.page(pageQuery, status, inboundType));
    }

    @GetMapping("/{id}")
    public Result<InboundDetailVO> get(@PathVariable Long id) {
        return Result.ok(inboundService.getDetail(id));
    }

    @PostMapping
    public Result<InboundDetailVO> create(@Valid @RequestBody InboundCreateRequest req) {
        InboundDetailVO vo = inboundService.create(req);
        operationLogService.record("inbound", "CREATE", vo.getId(), vo.getInboundNo(), OperatorContext.getOperatorName(), req);
        return Result.ok(vo);
    }

    /**
     * 编辑 / 流转入库单。
     *
     * <p>body 含 {@code action} 字段 → 流转（approve/check）；否则普通编辑（status 置 null 防越权）。
     */
    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (isTransition(body)) {
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> {
                    String auditLevel = body.get("auditLevel") != null ? String.valueOf(body.get("auditLevel")) : null;
                    inboundService.approve(id, auditLevel);
                    operationLogService.record("inbound", "APPROVE", id, null, OperatorContext.getOperatorName(), Map.of("auditLevel", auditLevel == null ? "" : auditLevel));
                }
                case "check" -> {
                    String checker = body.get("checker") != null ? String.valueOf(body.get("checker")) : null;
                    inboundService.check(id, checker);
                    operationLogService.record("inbound", "CHECK", id, null, OperatorContext.getOperatorName(), Map.of("checker", checker == null ? "" : checker));
                }
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        // 普通编辑：转 Entity，状态置 null 防越权
        Inbound entity = objectMapper.convertValue(body, Inbound.class);
        entity.setId(id);
        entity.setStatus(null);
        inboundService.updateById(entity);
        operationLogService.record("inbound", "UPDATE", id, entity.getInboundNo(), OperatorContext.getOperatorName(), entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Inbound existed = inboundService.getById(id);
        inboundService.deleteWithItems(id);
        operationLogService.record("inbound", "DELETE", id, existed != null ? existed.getInboundNo() : null, OperatorContext.getOperatorName(), null);
        return Result.ok();
    }

    /** 判断是否为流转请求：body 的所有 key 都属于流转字段集合 */
    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }


}
