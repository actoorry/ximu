package com.by.ximu.inventory.module.transfer;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.inventory.module.log.OperationLogService;
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

/** {@link TransferService} 状态流转并发回归测试：锁死 P0-1（approve/complete 的 updateById 返回 false 必须抛异常）。 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferItemMapper transferItemMapper;
    @Mock private OperationLogService operationLogService;
    @Mock private DocNoSequenceService docNoSequenceService;
    @Spy @InjectMocks private TransferService transferService;

    @AfterEach
    void tearDown() { OperatorContext.clear(); }

    @Test
    void approve_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
        Transfer transfer = new Transfer();
        transfer.setId(100L);
        transfer.setTransferNo("TR-TEST-001");
        transfer.setStatus("CREATED");
        transfer.setCreatedBy(2L);
        doReturn(transfer).when(transferService).getById(100L);
        doReturn(false).when(transferService).updateById(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transferService.approve(100L));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }

    @Test
    void complete_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "保管员", List.of("CHECKER")));
        Transfer transfer = new Transfer();
        transfer.setId(101L);
        transfer.setTransferNo("TR-TEST-002");
        transfer.setStatus("APPROVED");
        transfer.setCreatedBy(2L);
        doReturn(transfer).when(transferService).getById(101L);
        doReturn(false).when(transferService).updateById(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transferService.complete(101L));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }
}
