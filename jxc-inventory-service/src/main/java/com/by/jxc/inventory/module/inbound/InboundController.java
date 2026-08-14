package com.by.jxc.inventory.module.inbound;

import com.by.jxc.common.PageQuery;
import com.by.jxc.common.Result;
import com.by.jxc.inventory.module.log.OperationLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 入库 Controller。
 *
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
                                            @RequestParam(required = false) String inboundType,
                                            @RequestParam(required = false) String productName) {
        return Result.ok(inboundService.page(pageQuery, status, inboundType, productName));
    }

    @GetMapping("/{id}")
    public Result<Inbound> get(@PathVariable Long id) {
        return Result.ok(inboundService.getById(id));
    }

    @PostMapping
    public Result<Inbound> create(@RequestBody Inbound entity) {
        entity.setStatus("CREATED"); // 强制初始状态，防越权
        inboundService.save(entity);
        operationLogService.record("inbound", "CREATE", entity.getId(), entity.getInboundNo(), null, entity);
        return Result.ok(entity);
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
                    operationLogService.record("inbound", "APPROVE", id, null, operator(body), Map.of("auditLevel", auditLevel == null ? "" : auditLevel));
                }
                case "check" -> {
                    String checker = body.get("checker") != null ? String.valueOf(body.get("checker")) : null;
                    inboundService.check(id, checker);
                    operationLogService.record("inbound", "CHECK", id, null, checker != null ? checker : operator(body), Map.of("checker", checker == null ? "" : checker));
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
        operationLogService.record("inbound", "UPDATE", id, entity.getInboundNo(), null, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Inbound existed = inboundService.getById(id);
        inboundService.removeById(id);
        operationLogService.record("inbound", "DELETE", id, existed != null ? existed.getInboundNo() : null, null, null);
        return Result.ok();
    }

    /** 判断是否为流转请求：body 的所有 key 都属于流转字段集合 */
    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }

    private String operator(Map<String, Object> body) {
        return body.containsKey("operator") ? String.valueOf(body.get("operator")) : null;
    }
}
