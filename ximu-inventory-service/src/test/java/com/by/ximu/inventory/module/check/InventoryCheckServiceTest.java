package com.by.ximu.inventory.module.check;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.inventory.module.log.OperationLogService;
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/** {@link InventoryCheckService} 状态流转并发回归测试：锁死 P0-1（approve/check 的 updateById 返回 false 必须抛异常）。 */
@ExtendWith(MockitoExtension.class)
class InventoryCheckServiceTest {

    @Mock private CheckItemMapper checkItemMapper;
    @Mock private StockOperationService stockOperationService;
    @Mock private OperationLogService operationLogService;
    @Mock private DocNoSequenceService docNoSequenceService;
    @Spy @InjectMocks private InventoryCheckService inventoryCheckService;

    @AfterEach
    void tearDown() { OperatorContext.clear(); }

    @Test
    void approve_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
        InventoryCheck check = new InventoryCheck();
        check.setId(100L);
        check.setCheckNo("CK-TEST-001");
        check.setStatus("CREATED");
        check.setCreatedBy(2L);
        doReturn(check).when(inventoryCheckService).getById(100L);
        doReturn(false).when(inventoryCheckService).updateById(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inventoryCheckService.approve(100L));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }

    @Test
    void check_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "保管员", List.of("CHECKER")));
        InventoryCheck check = new InventoryCheck();
        check.setId(101L);
        check.setCheckNo("CK-TEST-002");
        check.setStatus("APPROVED");
        check.setCreatedBy(2L);
        doReturn(check).when(inventoryCheckService).getById(101L);
        doReturn(false).when(inventoryCheckService).updateById(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inventoryCheckService.check(101L));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }
}
