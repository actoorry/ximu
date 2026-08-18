package com.by.ximu.inventory.module.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.PageQuery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 操作日志服务：统一管理所有业务模块的操作日志记录与查询。
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
     * @param operation 操作类型（CREATE/UPDATE/DELETE/APPROVE/CHECK/COMPLETE）
     * @param targetId  目标单据 ID
     * @param targetNo  目标单据编号
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
     * 事务内记录一条操作日志（审计与业务同事务、同成败）。
     *
     * <p>与 {@link #record} 不同：本方法不吞异常，任何失败都会抛出并连带回滚调用方事务，
     * 保证「业务成功 ⇔ 审计成功」。仅限已开启事务的业务方法内调用；
     * 非事务上下文（如基础数据维护接口）请继续使用 {@link #record}。
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

    /**
     * 分页查询操作日志（支持按模块/操作类型/目标 ID 筛选）。
     */
    public Map<String, Object> page(PageQuery query, String module, String operation, Long targetId) {
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(module)) {
            wrapper.eq(OperationLog::getModule, module);
        }
        if (StringUtils.hasText(operation)) {
            wrapper.eq(OperationLog::getOperation, operation);
        }
        if (targetId != null) {
            wrapper.eq(OperationLog::getTargetId, targetId);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            // keyword 仅匹配单号/操作人（P1-1）：detail 为整段 JSON 大字段且无索引，
            // LIKE '%kw%' 全表扫描会随日志量增长线性变慢；内容检索需求应由
            // 日志平台（ELK 等）承担，不在业务库做。行为变化：keyword 不再命中 detail 内容。
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(OperationLog::getTargetNo, kw)
                    .or().like(OperationLog::getOperator, kw));
        }
        wrapper.orderByDesc(OperationLog::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    private Page<OperationLog> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
