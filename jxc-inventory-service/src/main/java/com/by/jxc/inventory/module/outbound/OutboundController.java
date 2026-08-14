package com.by.jxc.inventory.module.outbound;

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
 * 出库 Controller。
 *
 * <p>create 接收「头 + items」请求体，返回 DetailVO（头 + items + totalQty）；
 * get/list 同样返回头 + items/totalQty。
 * <p>流转字段集合：{@code id / status / action / operator}；
 * PUT /{id} 当 body 含 action 时走状态机流转，否则走普通编辑。
 */
@RestController
@RequestMapping("/api/inventory/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;
    private final OperationLogService operationLogService;
    private final ObjectMapper objectMapper;

    private static final Set<String> TRANSITION_KEYS = Set.of("id", "status", "action", "operator");

    @GetMapping
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String status) {
        return Result.ok(outboundService.page(pageQuery, status));
    }

    @GetMapping("/{id}")
    public Result<OutboundDetailVO> get(@PathVariable Long id) {
        return Result.ok(outboundService.getDetail(id));
    }

    @PostMapping
    public Result<OutboundDetailVO> create(@Valid @RequestBody OutboundCreateRequest req) {
        OutboundDetailVO vo = outboundService.create(req);
        operationLogService.record("outbound", "CREATE", vo.getId(), vo.getOutboundNo(), null, req);
        return Result.ok(vo);
    }

    @PutMapping("/{id}")
    public Result<Void> updateOrTransition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (isTransition(body)) {
            String action = String.valueOf(body.get("action"));
            switch (action) {
                case "approve" -> {
                    outboundService.approve(id);
                    operationLogService.record("outbound", "APPROVE", id, null, operator(body), null);
                }
                default -> throw new IllegalArgumentException("不支持的流转动作: " + action);
            }
            return Result.ok();
        }
        Outbound entity = objectMapper.convertValue(body, Outbound.class);
        entity.setId(id);
        entity.setStatus(null); // 防越权
        outboundService.updateById(entity);
        operationLogService.record("outbound", "UPDATE", id, entity.getOutboundNo(), null, entity);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Outbound existed = outboundService.getById(id);
        outboundService.removeById(id);
        operationLogService.record("outbound", "DELETE", id, existed != null ? existed.getOutboundNo() : null, null, null);
        return Result.ok();
    }

    private boolean isTransition(Map<String, Object> body) {
        return body.containsKey("action") && body.keySet().stream().allMatch(TRANSITION_KEYS::contains);
    }

    private String operator(Map<String, Object> body) {
        return body.containsKey("operator") ? String.valueOf(body.get("operator")) : null;
    }
}
