package com.by.jxc.inventory.module.outbound;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.jxc.common.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 出库服务：分页查询 + 状态机流转（CREATED → APPROVED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；非法迁移抛 {@link IllegalStateException}。
 */
@Service
public class OutboundService extends ServiceImpl<OutboundMapper, Outbound> {

    public Map<String, Object> page(PageQuery query, String status, String productName) {
        LambdaQueryWrapper<Outbound> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Outbound::getStatus, status);
        }
        if (StringUtils.hasText(productName)) {
            wrapper.like(Outbound::getProductName, productName);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Outbound::getOutboundNo, kw)
                    .or().like(Outbound::getProductName, kw)
                    .or().like(Outbound::getSaleOrderNo, kw));
        }
        wrapper.orderByDesc(Outbound::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    /**
     * 批准：CREATED → APPROVED。
     */
    @Transactional
    public void approve(Long id) {
        Outbound outbound = getById(id);
        if (outbound == null) {
            throw new IllegalArgumentException("出库单不存在: " + id);
        }
        if (!"CREATED".equals(outbound.getStatus())) {
            throw new IllegalStateException("当前状态[" + outbound.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        outbound.setStatus("APPROVED");
        updateById(outbound);
    }

    private Page<Outbound> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
