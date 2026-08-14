package com.by.jxc.inventory.module.log;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.jxc.common.PageQuery;
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
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(OperationLog::getTargetNo, kw)
                    .or().like(OperationLog::getOperator, kw)
                    .or().like(OperationLog::getDetail, kw));
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
