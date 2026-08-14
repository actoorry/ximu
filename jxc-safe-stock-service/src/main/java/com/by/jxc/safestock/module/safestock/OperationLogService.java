package com.by.jxc.safestock.module.safestock;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作日志服务：安全库存配置变更统一记录到 operation_log 表。
 *
 * <p>记录失败不影响主流程（异常被吞，仅打印 warn 日志）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService extends ServiceImpl<OperationLogMapper, OperationLog> {

    private final ObjectMapper objectMapper;

    /**
     * 记录一条操作日志。
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
}
