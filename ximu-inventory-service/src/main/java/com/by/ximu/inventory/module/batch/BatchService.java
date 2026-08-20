package com.by.ximu.inventory.module.batch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.PageQuery;
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
            // R2-P2-26：keyword 走 LIKE '%kw%'（前导通配）无法命中 V7 的 status/created_at 索引，
            // 数据量大时全表扫——当前单量级可接受；若成瓶颈改前缀索引/全文检索或对账侧拉数据
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(Batch::getBatchNo, kw)
                    .or().like(Batch::getProductName, kw)
                    .or().like(Batch::getCreator, kw));
        }
        wrapper.orderByDesc(Batch::getCreateDate);
        return query.toPageMap(baseMapper.selectPage(query.buildPage(), wrapper));
    }
}
