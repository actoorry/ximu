package com.by.ximu.inventory.module.outbound;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.inventory.module.log.OperationLogService;
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * {@link OutboundService} 状态流转并发回归测试。
 *
 * <p>锁死 P0-1 修复：approve 里 {@code updateById} 返回 false（乐观锁冲突，另一事务已改本单）时必须抛异常回滚，
 * 防止未来改回「不检查返回值」导致并发出库超卖。
 */
@ExtendWith(MockitoExtension.class)
class OutboundServiceTest {

    @Mock
    private OutboundItemMapper outboundItemMapper;

    @Mock
    private StockOperationService stockOperationService;

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private DocNoSequenceService docNoSequenceService;

    @Spy
    @InjectMocks
    private OutboundService outboundService;

    @BeforeEach
    void setUp() {
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    @Test
    void approve_乐观锁冲突_抛异常防并发超卖() {
        Outbound outbound = new Outbound();
        outbound.setId(100L);
        outbound.setOutboundNo("OUT-TEST-001");
        outbound.setStatus("CREATED");
        outbound.setCreatedBy(2L);

        doReturn(outbound).when(outboundService).getById(100L);
        doReturn(false).when(outboundService).updateById(any());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.approve(100L));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }
}
