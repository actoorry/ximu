package com.by.ximu.inventory.module.inbound;

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

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * {@link InboundService} 状态流转并发回归测试：锁死 P0-1（approve/check 的 updateById 返回 false 必须抛异常）。
 */
@ExtendWith(MockitoExtension.class)
class InboundServiceTest {

    @Mock private InboundItemMapper inboundItemMapper;
    @Mock private StockOperationService stockOperationService;
    @Mock private OperationLogService operationLogService;
    @Mock private DocNoSequenceService docNoSequenceService;
    @Spy @InjectMocks private InboundService inboundService;

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    @Test
    void approve_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
        Inbound inbound = new Inbound();
        inbound.setId(100L);
        inbound.setInboundNo("IN-TEST-001");
        inbound.setStatus("CREATED");
        inbound.setCreatedBy(2L);
        doReturn(inbound).when(inboundService).getById(100L);
        doReturn(false).when(inboundService).updateById(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inboundService.approve(100L, "总监审核"));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }

    @Test
    void check_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "保管员", List.of("CHECKER")));
        Inbound inbound = new Inbound();
        inbound.setId(101L);
        inbound.setInboundNo("IN-TEST-002");
        inbound.setStatus("APPROVED");
        inbound.setCreatedBy(2L);
        doReturn(inbound).when(inboundService).getById(101L);
        doReturn(false).when(inboundService).updateById(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inboundService.check(101L, "保管员"));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }
}
