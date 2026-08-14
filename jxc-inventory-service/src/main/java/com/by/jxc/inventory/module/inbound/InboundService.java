package com.by.jxc.inventory.module.inbound;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.jxc.common.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 入库服务：分页查询 + 状态机流转（CREATED → APPROVED → CHECKED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可审核；非法迁移抛 {@link IllegalStateException}。
 */
@Service
public class InboundService extends ServiceImpl<InboundMapper, Inbound> {

    /**
     * 分页查询（支持按状态/入库类型/商品名筛选 + keyword 模糊搜索）。
     */
    public Map<String, Object> page(PageQuery query, String status, String inboundType, String productName) {
        LambdaQueryWrapper<Inbound> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Inbound::getStatus, status);
        }
        if (StringUtils.hasText(inboundType)) {
            wrapper.eq(Inbound::getInboundType, inboundType);
        }
        if (StringUtils.hasText(productName)) {
            wrapper.like(Inbound::getProductName, productName);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Inbound::getInboundNo, kw)
                    .or().like(Inbound::getProductName, kw)
                    .or().like(Inbound::getSourceOrderNo, kw));
        }
        wrapper.orderByDesc(Inbound::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    /**
     * 批准：CREATED → APPROVED，并记录审核级别。
     */
    @Transactional
    public void approve(Long id, String auditLevel) {
        Inbound inbound = getById(id);
        if (inbound == null) {
            throw new IllegalArgumentException("入库单不存在: " + id);
        }
        if (!"CREATED".equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        inbound.setStatus("APPROVED");
        inbound.setAuditLevel(auditLevel);
        updateById(inbound);
    }

    /**
     * 审核：APPROVED → CHECKED，并记录审核人。
     */
    @Transactional
    public void check(Long id, String checker) {
        Inbound inbound = getById(id);
        if (inbound == null) {
            throw new IllegalArgumentException("入库单不存在: " + id);
        }
        if (!"APPROVED".equals(inbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + inbound.getStatus() + "]不允许审核，仅 APPROVED 状态可审核");
        }
        inbound.setStatus("CHECKED");
        inbound.setChecker(checker);
        updateById(inbound);
    }

    private Page<Inbound> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
