package com.by.ximu.inventory.module.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 库存统计服务：分页查询（库龄预警标记由 Controller 回填）。
 */
@Service
public class InventoryStockService extends ServiceImpl<InventoryStockMapper, InventoryStock> {

    public Map<String, Object> page(PageQuery query, String productName, String grade) {
        LambdaQueryWrapper<InventoryStock> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(productName)) {
            wrapper.like(InventoryStock::getProductName, productName);
        }
        if (StringUtils.hasText(grade)) {
            wrapper.like(InventoryStock::getGrade, grade);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(InventoryStock::getProductName, kw)
                    .or().like(InventoryStock::getSpec, kw)
                    .or().like(InventoryStock::getGrade, kw));
        }
        wrapper.orderByDesc(InventoryStock::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    private Page<InventoryStock> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
