package com.by.ximu.inventory.module.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.inventory.module.log.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 库存联动服务：出入库/盘点流转到终态时按 {@code org_id + product_name + spec + grade} 四维联动 {@code inventory_stock}。
 *
 * <p>本服务的三个方法均标注 {@code @Transactional}（默认 {@code REQUIRED}），
 * 由调用方（inbound.check / outbound.approve / check.check）保证「状态流转 + 库存联动」在同一事务内，
 * 任一步失败整体回滚，避免单据与库存不一致。
 *
 * <p>匹配规则：{@code org_id + product_name + material + spec + grade} 五维精确匹配；material/spec/grade 缺省（null）归一为空串。
 * 命中既有行则用 {@code @Version} 乐观锁更新；未命中则按入库/盘点语义新建一行。
 */
@Service
@RequiredArgsConstructor
public class StockOperationService {

    private final InventoryStockMapper inventoryStockMapper;
    private final OperationLogService operationLogService;

    /** 新建库存行时的默认库龄与预警阈值 */
    private static final int DEFAULT_STOCK_AGE = 0;
    private static final int DEFAULT_AGE_WARN_DAYS = 15;

    /**
     * 入库：{@code actual_qty += qty}；无匹配库存行则新建。
     *
     * @return 被更新的库存行（qty 为 0 或 null 时返回 null，不产生库存变化）
     */
    @Transactional
    public InventoryStock increaseStock(Long orgId, String grade, String productName, String material, String spec, BigDecimal qty) {
        requireOrg(orgId);
        requireProduct(productName);
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (qty.signum() < 0) {
            throw new IllegalArgumentException("入库数量必须为正数: " + qty);
        }
        InventoryStock stock = findStock(orgId, grade, productName, material, spec);
        if (stock == null) {
            stock = newStock(orgId, grade, productName, material, spec, qty);
            inventoryStockMapper.insert(stock);
        } else {
            BigDecimal now = stock.getActualQty() == null ? BigDecimal.ZERO : stock.getActualQty();
            stock.setActualQty(now.add(qty));
            if (inventoryStockMapper.updateById(stock) == 0) {
                throw new IllegalStateException("库存并发冲突，请重试（入库）");
            }
        }
        return stock;
    }

    /**
     * 出库：{@code actual_qty -= qty}；库存不足或无库存记录时抛 {@link IllegalStateException}。
     *
     * @return 被更新的库存行（qty 为 0 或 null 时返回 null，不产生库存变化）
     */
    @Transactional
    public InventoryStock decreaseStock(Long orgId, String grade, String productName, String material, String spec, BigDecimal qty) {
        requireOrg(orgId);
        requireProduct(productName);
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (qty.signum() < 0) {
            throw new IllegalArgumentException("出库数量必须为正数: " + qty);
        }
        InventoryStock stock = findStock(orgId, grade, productName, material, spec);
        BigDecimal available = stock == null || stock.getActualQty() == null
                ? BigDecimal.ZERO : stock.getActualQty();
        if (available.compareTo(qty) < 0) {
            throw new IllegalStateException("库存不足: " + productName
                    + (StringUtils.hasText(spec) ? "/" + spec : "")
                    + "，当前库存 " + available + "，需出库 " + qty);
        }
        stock.setActualQty(available.subtract(qty));
        if (inventoryStockMapper.updateById(stock) == 0) {
            // 并发场景：另一事务已修改本行（version 变化），本次扣减未生效；
            // 抛异常使调用方事务（outbound.approve）整体回滚，避免「单据已流转但库存未扣」的超卖。
            throw new IllegalStateException("库存并发冲突，请重试（出库）: " + productName);
        }
        return stock;
    }

    /**
     * 盘点：{@code actual_qty = actualQty}（直接校正到实盘值）；无匹配库存行则新建。
     *
     * @return 被更新的库存行（actualQty 为 null 时返回 null，不产生库存变化）
     */
    @Transactional
    public InventoryStock adjustStock(Long orgId, String grade, String productName, String material, String spec, BigDecimal actualQty) {
        requireOrg(orgId);
        requireProduct(productName);
        if (actualQty == null) {
            return null;
        }
        if (actualQty.signum() < 0) {
            throw new IllegalArgumentException("盘点实盘数量不能为负: " + actualQty);
        }
        InventoryStock stock = findStock(orgId, grade, productName, material, spec);
        if (stock == null) {
            stock = newStock(orgId, grade, productName, material, spec, actualQty);
            inventoryStockMapper.insert(stock);
        } else {
            BigDecimal before = stock.getActualQty() == null ? BigDecimal.ZERO : stock.getActualQty();
            stock.setActualQty(actualQty);
            if (inventoryStockMapper.updateById(stock) == 0) {
                throw new IllegalStateException("库存并发冲突，请重试（盘点）");
            }
            // 盘盈盘亏流水：账面与实盘差异落审计（正值盘盈、负值盘亏）
            BigDecimal diff = actualQty.subtract(before);
            if (diff.signum() != 0) {
                operationLogService.recordInTx("stock", "ADJUST", stock.getId(), productName,
                        OperatorContext.getOperatorName(),
                        Map.of("before", before, "after", actualQty, "diff", diff));
            }
        }
        return stock;
    }

    /** 组织维度为空时拒绝，避免四维键缺失导致错配 */
    private void requireOrg(Long orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("组织(orgId)不能为空");
        }
    }

    /** 品名为空时直接拒绝，避免 null 品名联动到空库存行导致错配 */
    private void requireProduct(String productName) {
        if (!StringUtils.hasText(productName)) {
            throw new IllegalArgumentException("品名不能为空");
        }
    }

    /** 按 org_id + product_name + material + spec + grade 五维精确匹配单行库存（material/spec/grade 缺省归一为空串） */
    private InventoryStock findStock(Long orgId, String grade, String productName, String material, String spec) {
        LambdaQueryWrapper<InventoryStock> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryStock::getOrgId, orgId);
        wrapper.eq(InventoryStock::getProductName, productName);
        wrapper.eq(InventoryStock::getMaterial, material == null ? "" : material);
        wrapper.eq(InventoryStock::getSpec, spec == null ? "" : spec);
        wrapper.eq(InventoryStock::getGrade, grade == null ? "" : grade);
        wrapper.last("LIMIT 1");
        return inventoryStockMapper.selectOne(wrapper);
    }

    /** 构造一条新建库存行（spec/grade 缺省归一为空串，其余走表默认值或业务默认值） */
    private InventoryStock newStock(Long orgId, String grade, String productName, String material, String spec, BigDecimal actualQty) {
        InventoryStock stock = new InventoryStock();
        stock.setOrgId(orgId);
        stock.setProductName(productName);
        stock.setMaterial(material == null ? "" : material);
        stock.setSpec(spec == null ? "" : spec);
        stock.setGrade(grade == null ? "" : grade);
        stock.setActualQty(actualQty);
        stock.setTransitQty(BigDecimal.ZERO);
        stock.setStockAge(DEFAULT_STOCK_AGE);
        stock.setAgeWarnDays(DEFAULT_AGE_WARN_DAYS);
        stock.setFirstInboundAt(LocalDateTime.now());
        return stock;
    }
}
