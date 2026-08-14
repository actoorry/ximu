package com.by.jxc.inventory.module.batch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.jxc.common.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 批号服务：分页查询（支持批号/商品名筛选 + keyword 模糊）。
 */
@Service
public class BatchService extends ServiceImpl<BatchMapper, Batch> {

    public Map<String, Object> page(PageQuery query, String productName, String batchNo) {
        LambdaQueryWrapper<Batch> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(productName)) {
            wrapper.like(Batch::getProductName, productName);
        }
        if (StringUtils.hasText(batchNo)) {
            wrapper.like(Batch::getBatchNo, batchNo);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Batch::getBatchNo, kw)
                    .or().like(Batch::getProductName, kw)
                    .or().like(Batch::getCreator, kw));
        }
        wrapper.orderByDesc(Batch::getCreateDate);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    private Page<Batch> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
