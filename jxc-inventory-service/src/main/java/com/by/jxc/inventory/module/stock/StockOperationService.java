package com.by.jxc.inventory.module.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 库存联动服务：出入库/盘点流转到终态时按 {@code product_name + spec} 联动 {@code inventory_stock}。
 *
 * <p>本服务的三个方法均标注 {@code @Transactional}（默认 {@code REQUIRED}），
 * 由调用方（inbound.check / outbound.approve / check.check）保证「状态流转 + 库存联动」在同一事务内，
 * 任一步失败整体回滚，避免单据与库存不一致。
 *
 * <p>匹配规则：{@code product_name} 精确匹配 + {@code spec} 精确匹配（spec 为 null 时按 {@code IS NULL} 匹配）。
 * 命中既有行则用 {@code @Version} 乐观锁更新；未命中则按入库/盘点语义新建一行。
 */
@Service
@RequiredArgsConstructor
public class StockOperationService {

    private final InventoryStockMapper inventoryStockMapper;

    /** 新建库存行时的默认库龄与预警阈值 */
    private static final int DEFAULT_STOCK_AGE = 0;
    private static final int DEFAULT_AGE_WARN_DAYS = 15;

    /**
     * 入库：{@code actual_qty += qty}；无匹配库存行则新建。
     *
     * @return 被更新的库存行（qty 为 0 或 null 时返回 null，不产生库存变化）
     */
    @Transactional
    public InventoryStock increaseStock(String productName, String spec, BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        InventoryStock stock = findStock(productName, spec);
        if (stock == null) {
            stock = newStock(productName, spec, qty);
            inventoryStockMapper.insert(stock);
        } else {
            BigDecimal now = stock.getActualQty() == null ? BigDecimal.ZERO : stock.getActualQty();
            stock.setActualQty(now.add(qty));
            inventoryStockMapper.updateById(stock); // @Version 乐观锁
        }
        return stock;
    }

    /**
     * 出库：{@code actual_qty -= qty}；库存不足或无库存记录时抛 {@link IllegalStateException}。
     *
     * @return 被更新的库存行（qty 为 0 或 null 时返回 null，不产生库存变化）
     */
    @Transactional
    public InventoryStock decreaseStock(String productName, String spec, BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        InventoryStock stock = findStock(productName, spec);
        BigDecimal available = stock == null || stock.getActualQty() == null
                ? BigDecimal.ZERO : stock.getActualQty();
        if (available.compareTo(qty) < 0) {
            throw new IllegalStateException("库存不足: " + productName
                    + (StringUtils.hasText(spec) ? "/" + spec : "")
                    + "，当前库存 " + available + "，需出库 " + qty);
        }
        stock.setActualQty(available.subtract(qty));
        inventoryStockMapper.updateById(stock); // @Version 乐观锁
        return stock;
    }

    /**
     * 盘点：{@code actual_qty = actualQty}（直接校正到实盘值）；无匹配库存行则新建。
     *
     * @return 被更新的库存行（actualQty 为 null 时返回 null，不产生库存变化）
     */
    @Transactional
    public InventoryStock adjustStock(String productName, String spec, BigDecimal actualQty) {
        if (actualQty == null) {
            return null;
        }
        InventoryStock stock = findStock(productName, spec);
        if (stock == null) {
            stock = newStock(productName, spec, actualQty);
            inventoryStockMapper.insert(stock);
        } else {
            stock.setActualQty(actualQty);
            inventoryStockMapper.updateById(stock); // @Version 乐观锁
        }
        return stock;
    }

    /** 按 product_name + spec 精确匹配单行库存（值为 null 时用 IS NULL，避免 = NULL 永不命中导致重复新建） */
    private InventoryStock findStock(String productName, String spec) {
        LambdaQueryWrapper<InventoryStock> wrapper = new LambdaQueryWrapper<>();
        if (productName == null) {
            wrapper.isNull(InventoryStock::getProductName);
        } else {
            wrapper.eq(InventoryStock::getProductName, productName);
        }
        if (spec == null) {
            wrapper.isNull(InventoryStock::getSpec);
        } else {
            wrapper.eq(InventoryStock::getSpec, spec);
        }
        wrapper.last("LIMIT 1");
        return inventoryStockMapper.selectOne(wrapper);
    }

    /** 构造一条新建库存行（仅设置联动相关字段，其余走表默认值或业务默认值） */
    private InventoryStock newStock(String productName, String spec, BigDecimal actualQty) {
        InventoryStock stock = new InventoryStock();
        stock.setProductName(productName);
        stock.setSpec(spec);
        stock.setActualQty(actualQty);
        stock.setTransitQty(BigDecimal.ZERO);
        stock.setStockAge(DEFAULT_STOCK_AGE);
        stock.setAgeWarnDays(DEFAULT_AGE_WARN_DAYS);
        return stock;
    }
}
