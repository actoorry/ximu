package com.by.jxc.inventory.module.check;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.jxc.common.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 盘点服务：分页查询 + 状态机流转（CREATED → APPROVED → CHECKED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可审核；非法迁移抛 {@link IllegalStateException}。
 */
@Service
public class InventoryCheckService extends ServiceImpl<InventoryCheckMapper, InventoryCheck> {

    public Map<String, Object> page(PageQuery query, String status, String batchNo) {
        LambdaQueryWrapper<InventoryCheck> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(InventoryCheck::getStatus, status);
        }
        if (StringUtils.hasText(batchNo)) {
            wrapper.eq(InventoryCheck::getBatchNo, batchNo);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(InventoryCheck::getCheckNo, kw)
                    .or().like(InventoryCheck::getBatchNo, kw));
        }
        wrapper.orderByDesc(InventoryCheck::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    /**
     * 批准：CREATED → APPROVED。
     */
    @Transactional
    public void approve(Long id) {
        InventoryCheck check = getById(id);
        if (check == null) {
            throw new IllegalArgumentException("盘点单不存在: " + id);
        }
        if (!"CREATED".equals(check.getStatus())) {
            throw new IllegalStateException("当前状态[" + check.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        check.setStatus("APPROVED");
        updateById(check);
    }

    /**
     * 审核：APPROVED → CHECKED。
     */
    @Transactional
    public void check(Long id) {
        InventoryCheck check = getById(id);
        if (check == null) {
            throw new IllegalArgumentException("盘点单不存在: " + id);
        }
        if (!"APPROVED".equals(check.getStatus())) {
            throw new IllegalStateException("当前状态[" + check.getStatus() + "]不允许审核，仅 APPROVED 状态可审核");
        }
        check.setStatus("CHECKED");
        updateById(check);
    }

    private Page<InventoryCheck> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
