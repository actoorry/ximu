package com.by.ximu.inventory.module.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
            // R2-P2-26：keyword 走 LIKE '%kw%'（前导通配）无法命中 V7 的 status/created_at 索引，
            // 数据量大时全表扫——当前单量级可接受；若成瓶颈改前缀索引/全文检索或对账侧拉数据
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(InventoryStock::getProductName, kw)
                    .or().like(InventoryStock::getSpec, kw)
                    .or().like(InventoryStock::getGrade, kw));
        }
        wrapper.orderByDesc(InventoryStock::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(query.buildPage(), wrapper));
    }
}
