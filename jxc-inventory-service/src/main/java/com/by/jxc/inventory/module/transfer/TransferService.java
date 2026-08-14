package com.by.jxc.inventory.module.transfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.jxc.common.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 调拨服务：分页查询 + 状态机流转（CREATED → APPROVED → COMPLETED）。
 *
 * <p>流转前置校验：仅 CREATED 可批准；仅 APPROVED 可完成；非法迁移抛 {@link IllegalStateException}。
 */
@Service
public class TransferService extends ServiceImpl<TransferMapper, Transfer> {

    public Map<String, Object> page(PageQuery query, String status, String targetLocation) {
        LambdaQueryWrapper<Transfer> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(Transfer::getStatus, status);
        }
        if (StringUtils.hasText(targetLocation)) {
            wrapper.like(Transfer::getTargetLocation, targetLocation);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Transfer::getTransferNo, kw)
                    .or().like(Transfer::getBatchNo, kw)
                    .or().like(Transfer::getTargetLocation, kw));
        }
        wrapper.orderByDesc(Transfer::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    /**
     * 批准：CREATED → APPROVED。
     */
    @Transactional
    public void approve(Long id) {
        Transfer transfer = getById(id);
        if (transfer == null) {
            throw new IllegalArgumentException("调拨单不存在: " + id);
        }
        if (!"CREATED".equals(transfer.getStatus())) {
            throw new IllegalStateException("当前状态[" + transfer.getStatus() + "]不允许批准，仅 CREATED 状态可批准");
        }
        transfer.setStatus("APPROVED");
        updateById(transfer);
    }

    /**
     * 完成：APPROVED → COMPLETED。
     */
    @Transactional
    public void complete(Long id) {
        Transfer transfer = getById(id);
        if (transfer == null) {
            throw new IllegalArgumentException("调拨单不存在: " + id);
        }
        if (!"APPROVED".equals(transfer.getStatus())) {
            throw new IllegalStateException("当前状态[" + transfer.getStatus() + "]不允许完成，仅 APPROVED 状态可完成");
        }
        transfer.setStatus("COMPLETED");
        updateById(transfer);
    }

    private Page<Transfer> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
