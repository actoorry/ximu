package com.by.ximu.safestock.module.safestock;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作日志服务：安全库存配置变更统一记录到 operation_log 表。
 *
 * <p>业务写操作（Controller 的 create/update/delete）一律走 {@link #recordInTx}——与业务写同事务，
 * 审计失败即回滚业务（P2-7，不再"改了数据、丢审计"）；非事务上下文的兜底才用 {@link #record}。
 *
 * <p>本类与 inventory-service 的 OperationLogService 写同一张物理表（同库 ximu），
 * 字段/语义改动必须两侧同步（P2-9 契约约束，V9 加 operator_id 时同步处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService extends ServiceImpl<OperationLogMapper, OperationLog> {

    private final ObjectMapper objectMapper;

    /**
     * 记录一条操作日志（吞异常版，仅限非事务上下文兜底）。
     *
     * @param module    业务模块标识
     * @param operation 操作类型（CREATE/UPDATE/DELETE）
     * @param targetId  目标单据 ID
     * @param targetNo  目标单据标识
     * @param operator  操作人
     * @param detail    操作详情（任意对象，序列化为 JSON；可为 null）
     */
    public void record(String module, String operation, Long targetId, String targetNo, String operator, Object detail) {
        try {
            OperationLog entity = new OperationLog();
            entity.setModule(module);
            entity.setOperation(operation);
            entity.setTargetId(targetId);
            entity.setTargetNo(targetNo);
            entity.setOperator(operator);
            entity.setDetail(detail == null ? null : objectMapper.writeValueAsString(detail));
            save(entity);
        } catch (Exception e) {
            log.warn("记录操作日志失败: module={}, operation={}, err={}", module, operation, e.getMessage());
        }
    }

    /**
     * 事务内记录操作日志（P2-7）：不吞异常——序列化或落库失败直接抛出，随事务回滚业务写。
     * 调用方必须处于 @Transactional 上下文，否则失去"业务与审计同生共死"语义。
     */
    public void recordInTx(String module, String operation, Long targetId, String targetNo, String operator, Object detail) {
        OperationLog entity = new OperationLog();
        entity.setModule(module);
        entity.setOperation(operation);
        entity.setTargetId(targetId);
        entity.setTargetNo(targetNo);
        entity.setOperator(operator);
        entity.setDetail(writeDetail(detail));
        save(entity);
    }

    private String writeDetail(Object detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计详情序列化失败", e);
        }
    }
}
