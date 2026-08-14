package com.by.ximu.safestock.module.safestock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.by.ximu.common.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 安全库存服务：分页查询（支持商品名/物料筛选 + keyword 模糊）。
 */
@Service
public class SafeStockService extends ServiceImpl<SafeStockMapper, SafeStock> {

    public Map<String, Object> page(PageQuery query, String productName, String material) {
        LambdaQueryWrapper<SafeStock> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(productName)) {
            wrapper.like(SafeStock::getProductName, productName);
        }
        if (StringUtils.hasText(material)) {
            wrapper.like(SafeStock::getMaterial, material);
        }
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w.like(SafeStock::getProductName, kw)
                    .or().like(SafeStock::getMaterial, kw));
        }
        wrapper.orderByDesc(SafeStock::getCreatedAt);
        return query.toPageMap(baseMapper.selectPage(buildPage(query), wrapper));
    }

    private Page<SafeStock> buildPage(PageQuery query) {
        int p = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int s = query.getSize() == null || query.getSize() < 1 ? 10 : query.getSize();
        s = Math.min(s, 200);
        return new Page<>(p, s);
    }
}
