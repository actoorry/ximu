package com.by.ximu.inventory.module.stock;

import com.by.ximu.inventory.module.log.OperationLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link StockOperationService} 的纯单元测试（Mockito mock {@link InventoryStockMapper}，不依赖 DB / Spring 上下文）。
 *
 * <p>覆盖 increase / decrease / adjust 三个方法：五维匹配命中与未命中分支、material/spec/grade null 归一为空串、
 * 负数 / 0 / null 数量的拒绝或忽略、库存不足异常（含品名信息）、orgId / 品名为空拒绝。
 */
@ExtendWith(MockitoExtension.class)
class StockOperationServiceTest {

    @Mock
    private InventoryStockMapper inventoryStockMapper;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private StockOperationService stockOperationService;

    /** 构造一条已存在的库存行（便于命中既有行分支） */
    private InventoryStock existingStock(BigDecimal actualQty) {
        InventoryStock stock = new InventoryStock();
        stock.setId(100L);
        stock.setOrgId(1L);
        stock.setProductName("苹果");
        stock.setSpec("");
        stock.setGrade("");
        stock.setActualQty(actualQty);
        return stock;
    }

    // ---------- increase ----------

    @Test
    void increase_无匹配行_新建并归一spec与grade_且firstInboundAt非空() {
        when(inventoryStockMapper.selectOne(any())).thenReturn(null);

        InventoryStock result = stockOperationService.increaseStock(1L, null, "苹果", null, null, new BigDecimal("10"));

        assertNotNull(result);
        assertNull(result.getId());
        assertEquals("苹果", result.getProductName());
        assertEquals("", result.getSpec());
        assertEquals("", result.getGrade());
        assertEquals(0, result.getActualQty().compareTo(new BigDecimal("10")));
        assertEquals(0, result.getTransitQty().compareTo(BigDecimal.ZERO));
        assertEquals(Integer.valueOf(0), result.getStockAge());
        assertEquals(Integer.valueOf(15), result.getAgeWarnDays());
        assertNotNull(result.getFirstInboundAt());
        verify(inventoryStockMapper).insert(result);
        verify(inventoryStockMapper, never()).updateById(any(InventoryStock.class));
    }

    @Test
    void increase_非空spec与grade_原样保留() {
        when(inventoryStockMapper.selectOne(any())).thenReturn(null);

        InventoryStock result = stockOperationService.increaseStock(1L, "A级", "苹果", null, "大", new BigDecimal("10"));

        assertEquals("A级", result.getGrade());
        assertEquals("大", result.getSpec());
        verify(inventoryStockMapper).insert(result);
    }

    @Test
    void increase_命中既有行_累加实际库存() {
        InventoryStock existing = existingStock(new BigDecimal("5"));
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);
        when(inventoryStockMapper.updateById(existing)).thenReturn(1);

        InventoryStock result = stockOperationService.increaseStock(1L, "A级", "苹果", null, "大", new BigDecimal("3"));

        assertSame(existing, result);
        assertEquals(0, result.getActualQty().compareTo(new BigDecimal("8")));
        verify(inventoryStockMapper).updateById(existing);
        verify(inventoryStockMapper, never()).insert(any(InventoryStock.class));
    }

    @Test
    void increase_既有行实际库存为null_按0累加() {
        InventoryStock existing = existingStock(null);
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);
        when(inventoryStockMapper.updateById(existing)).thenReturn(1);

        InventoryStock result = stockOperationService.increaseStock(1L, null, "苹果", null, null, new BigDecimal("3"));

        assertEquals(0, result.getActualQty().compareTo(new BigDecimal("3")));
    }

    @Test
    void increase_数量为负数_拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stockOperationService.increaseStock(1L, null, "苹果", null, null, new BigDecimal("-1")));
        assertTrue(ex.getMessage().contains("入库数量必须为正数"));
    }

    @Test
    void increase_数量为0_返回null且不操作库() {
        assertNull(stockOperationService.increaseStock(1L, null, "苹果", null, null, BigDecimal.ZERO));
        verifyNoInteractions(inventoryStockMapper);
    }

    @Test
    void increase_数量为null_返回null且不操作库() {
        assertNull(stockOperationService.increaseStock(1L, null, "苹果", null, null, null));
        verifyNoInteractions(inventoryStockMapper);
    }

    @Test
    void increase_orgId为null_拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stockOperationService.increaseStock(null, null, "苹果", null, null, new BigDecimal("1")));
        assertTrue(ex.getMessage().contains("组织"));
        verifyNoInteractions(inventoryStockMapper);
    }

    @Test
    void increase_品名为空_拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stockOperationService.increaseStock(1L, null, "  ", null, null, new BigDecimal("1")));
        assertTrue(ex.getMessage().contains("品名"));
        verifyNoInteractions(inventoryStockMapper);
    }

    // ---------- decrease ----------

    @Test
    void decrease_库存不足_抛异常且含品名与规格() {
        InventoryStock existing = existingStock(new BigDecimal("2"));
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stockOperationService.decreaseStock(1L, null, "苹果", null, "大", new BigDecimal("5")));

        assertTrue(ex.getMessage().contains("苹果"));
        assertTrue(ex.getMessage().contains("/大"));
        assertTrue(ex.getMessage().contains("当前库存 2"));
        assertTrue(ex.getMessage().contains("需出库 5"));
    }

    @Test
    void decrease_无库存记录_视为0并抛库存不足() {
        when(inventoryStockMapper.selectOne(any())).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stockOperationService.decreaseStock(1L, null, "苹果", null, null, new BigDecimal("1")));

        assertTrue(ex.getMessage().contains("当前库存 0"));
        assertTrue(ex.getMessage().contains("苹果"));
    }

    @Test
    void decrease_库存充足_正常扣减() {
        InventoryStock existing = existingStock(new BigDecimal("10"));
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);
        when(inventoryStockMapper.updateById(existing)).thenReturn(1);

        InventoryStock result = stockOperationService.decreaseStock(1L, null, "苹果", null, null, new BigDecimal("4"));

        assertSame(existing, result);
        assertEquals(0, result.getActualQty().compareTo(new BigDecimal("6")));
        verify(inventoryStockMapper).updateById(existing);
        verify(inventoryStockMapper, never()).insert(any(InventoryStock.class));
    }

    @Test
    void decrease_数量为负数_拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stockOperationService.decreaseStock(1L, null, "苹果", null, null, new BigDecimal("-1")));
        assertTrue(ex.getMessage().contains("出库数量必须为正数"));
    }

    @Test
    void decrease_数量为0或null_返回null且不操作库() {
        assertNull(stockOperationService.decreaseStock(1L, null, "苹果", null, null, BigDecimal.ZERO));
        assertNull(stockOperationService.decreaseStock(1L, null, "苹果", null, null, null));
        verifyNoInteractions(inventoryStockMapper);
    }

    // ---------- adjust ----------

    @Test
    void adjust_命中既有行_校正到实盘值() {
        InventoryStock existing = existingStock(new BigDecimal("10"));
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);
        when(inventoryStockMapper.updateById(existing)).thenReturn(1);

        InventoryStock result = stockOperationService.adjustStock(1L, null, "苹果", null, null, new BigDecimal("3"));

        assertSame(existing, result);
        assertEquals(0, result.getActualQty().compareTo(new BigDecimal("3")));
        verify(inventoryStockMapper).updateById(existing);
        verify(inventoryStockMapper, never()).insert(any(InventoryStock.class));
    }

    @Test
    void adjust_无匹配行_新建() {
        when(inventoryStockMapper.selectOne(any())).thenReturn(null);

        InventoryStock result = stockOperationService.adjustStock(1L, "B级", "苹果", null, "大", new BigDecimal("7"));

        assertNotNull(result);
        assertEquals("B级", result.getGrade());
        assertEquals("大", result.getSpec());
        assertEquals(0, result.getActualQty().compareTo(new BigDecimal("7")));
        assertNotNull(result.getFirstInboundAt());
        verify(inventoryStockMapper).insert(result);
    }

    @Test
    void adjust_实盘数量为0_新建零库存行() {
        when(inventoryStockMapper.selectOne(any())).thenReturn(null);

        InventoryStock result = stockOperationService.adjustStock(1L, null, "苹果", null, null, BigDecimal.ZERO);

        assertNotNull(result);
        assertEquals(0, result.getActualQty().compareTo(BigDecimal.ZERO));
        verify(inventoryStockMapper).insert(result);
    }

    @Test
    void adjust_实盘数量为负数_拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stockOperationService.adjustStock(1L, null, "苹果", null, null, new BigDecimal("-1")));
        assertTrue(ex.getMessage().contains("盘点实盘数量不能为负"));
    }

    @Test
    void adjust_实盘数量为null_返回null且不操作库() {
        assertNull(stockOperationService.adjustStock(1L, null, "苹果", null, null, null));
        verifyNoInteractions(inventoryStockMapper);
    }

    @Test
    void adjust_orgId为null_拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stockOperationService.adjustStock(null, null, "苹果", null, null, new BigDecimal("1")));
        assertTrue(ex.getMessage().contains("组织"));
        verifyNoInteractions(inventoryStockMapper);
    }

    @Test
    void adjust_品名为空_拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> stockOperationService.adjustStock(1L, null, "", null, null, new BigDecimal("1")));
        assertTrue(ex.getMessage().contains("品名"));
        verifyNoInteractions(inventoryStockMapper);
    }

    // ---------- 并发冲突（乐观锁 updateById 返回 false → 抛异常，防超卖） ----------

    @Test
    void increase_乐观锁冲突_抛异常() {
        InventoryStock existing = existingStock(new BigDecimal("5"));
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);
        when(inventoryStockMapper.updateById(existing)).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stockOperationService.increaseStock(1L, null, "苹果", null, null, new BigDecimal("3")));
        assertTrue(ex.getMessage().contains("并发冲突"));
    }

    @Test
    void decrease_乐观锁冲突_抛异常防超卖() {
        InventoryStock existing = existingStock(new BigDecimal("10"));
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);
        when(inventoryStockMapper.updateById(existing)).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stockOperationService.decreaseStock(1L, null, "苹果", null, null, new BigDecimal("8")));
        assertTrue(ex.getMessage().contains("并发冲突"));
        assertTrue(ex.getMessage().contains("出库"));
    }

    @Test
    void adjust_乐观锁冲突_抛异常() {
        InventoryStock existing = existingStock(new BigDecimal("10"));
        when(inventoryStockMapper.selectOne(any())).thenReturn(existing);
        when(inventoryStockMapper.updateById(existing)).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stockOperationService.adjustStock(1L, null, "苹果", null, null, new BigDecimal("3")));
        assertTrue(ex.getMessage().contains("并发冲突"));
    }
}