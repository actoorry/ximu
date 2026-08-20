package com.by.ximu.inventory.module.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.Role;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.Result;
import com.by.ximu.common.web.audit.OperationLogService;
import com.by.ximu.common.web.security.RequireRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 库存统计 Controller。
 *
 * <p>列表查询时先回填库龄天数 stockAgeDays，再回填库龄预警标记 warn（V10 删静态列后仅动态判定）：
 * {@code warn = stockAgeDays != null && stockAgeDays >= ageWarnDays}，ageWarnDays 为 null 时为 false。
 */
@RestController
@RequestMapping("/api/inventory/stock")
@RequiredArgsConstructor
public class InventoryStockController {

    private final InventoryStockService inventoryStockService;
    private final OperationLogService operationLogService;

    @GetMapping
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<Map<String, Object>> list(PageQuery pageQuery,
                                            @RequestParam(required = false) String productName,
                                            @RequestParam(required = false) String grade) {
        Map<String, Object> result = inventoryStockService.page(pageQuery, productName, grade);
        fillStockAgeDays(result);
        fillWarn(result);
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    @RequireRole({Role.VIEWER, Role.ADMIN})
    public Result<InventoryStock> get(@PathVariable Long id) {
        InventoryStock stock = inventoryStockService.getById(id);
        if (stock == null) {
            throw new NoSuchElementException("库存记录不存在: " + id);
        }
        fillStockAgeDays(stock);
        stock.setWarn(isWarn(stock));
        return Result.ok(stock);
    }

    @Transactional
    @PostMapping
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<InventoryStock> create(@Valid @RequestBody InventoryStockCreateRequest req) {
        // 白名单赋值：只建立库存维度行（orgId/品名/材质/规格/等级 + 预警配置）；
        // 账本字段（actualQty/firstInboundAt）由单据流转产生，不在此接收。
        InventoryStock entity = new InventoryStock();
        entity.setOrgId(req.getOrgId());
        entity.setProductName(req.getProductName());
        entity.setGrade(req.getGrade() == null ? "" : req.getGrade());
        entity.setMaterial(req.getMaterial() == null ? "" : req.getMaterial());
        entity.setSpec(req.getSpec() == null ? "" : req.getSpec());
        entity.setAgeWarnDays(req.getAgeWarnDays());
        inventoryStockService.save(entity);
        operationLogService.recordInTx("stock", "CREATE", entity.getId(), entity.getProductName(), OperatorContext.getOperatorName(), req);
        return Result.ok(entity);
    }

    @Transactional
    @PutMapping("/{id}")
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody InventoryStockUpdateRequest req) {
        InventoryStock existed = inventoryStockService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("库存记录不存在: " + id);
        }
        // 白名单：库存账本字段（actualQty/orgId/productName/material/spec/grade/firstInboundAt）只能由单据流转产生，禁止直接 PUT 修改；
        // 仅允许修改库龄预警配置（ageWarnDays 阈值）。
        // R2-P2-18：改白名单 DTO 接收（仅 ageWarnDays，@Min(0)@Max(365)）+ 部分更新语义：字段为 null 表示保持原值（与单据 updateHead 一致），
        // 避免误清空预警阈值导致预警静默失效，也不再接收原始实体暴露账本字段。
        if (req.getAgeWarnDays() != null) {
            existed.setAgeWarnDays(req.getAgeWarnDays());
        }
        if (!inventoryStockService.updateById(existed)) {
            throw new IllegalStateException("库存并发冲突，请刷新后重试");
        }
        operationLogService.recordInTx("stock", "UPDATE", id, existed.getProductName(), OperatorContext.getOperatorName(), existed);
        return Result.ok();
    }

    @Transactional
    @DeleteMapping("/{id}")
    @RequireRole({Role.CHECKER, Role.ADMIN})
    public Result<Void> delete(@PathVariable Long id) {
        InventoryStock existed = inventoryStockService.getById(id);
        if (existed == null) {
            throw new IllegalArgumentException("库存记录不存在: " + id);
        }
        // 业务约束：实际库存非 0 不可删除，避免删掉正在被单据引用的库存行（V10 已删 transit_qty，仅判实际库存）
        if (existed.getActualQty() != null && existed.getActualQty().compareTo(java.math.BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("库存不为零，不可删除（请通过出库/调拨流转处理）");
        }
        // 条件删除（P0-3）：数量为 0 的约束随删除语句原子下发，
        // 堵住「读到 0 → 并发单据联动改数量 → 仍删除」的竞态窗口（actual_qty NOT NULL，eq 0 无 NULL 陷阱）
        boolean removed = inventoryStockService.remove(new LambdaQueryWrapper<InventoryStock>()
                .eq(InventoryStock::getId, id)
                .eq(InventoryStock::getActualQty, java.math.BigDecimal.ZERO));
        if (!removed) {
            throw new IllegalStateException("库存不为零或并发冲突，不可删除（请通过出库/调拨流转处理）");
        }
        operationLogService.recordInTx("stock", "DELETE", id, existed.getProductName(), OperatorContext.getOperatorName(), null);
        return Result.ok();
    }

    /** 遍历列表回填 warn 字段 */
    @SuppressWarnings("unchecked")
    private void fillWarn(Map<String, Object> result) {
        Object listObj = result.get("list");
        if (listObj instanceof List<?>) {
            for (Object item : (List<?>) listObj) {
                if (item instanceof InventoryStock s) {
                    s.setWarn(isWarn(s));
                }
            }
        }
    }

    private boolean isWarn(InventoryStock s) {
        return InventoryStock.isWarn(s.getStockAgeDays(), s.getAgeWarnDays());
    }

    /** 遍历列表回填库龄天数 stockAgeDays */
    @SuppressWarnings("unchecked")
    private void fillStockAgeDays(Map<String, Object> result) {
        Object listObj = result.get("list");
        if (listObj instanceof List<?>) {
            for (Object item : (List<?>) listObj) {
                if (item instanceof InventoryStock s) {
                    fillStockAgeDays(s);
                }
            }
        }
    }

    /** 单行回填库龄天数（now - firstInboundAt 向下取整，firstInboundAt 为 null 时为 null） */
    private void fillStockAgeDays(InventoryStock s) {
        s.setStockAgeDays(InventoryStock.stockAgeDays(s.getFirstInboundAt(), LocalDateTime.now()));
    }
}
