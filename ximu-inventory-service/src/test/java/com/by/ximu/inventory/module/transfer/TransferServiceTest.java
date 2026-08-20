package com.by.ximu.inventory.module.transfer;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.web.audit.OperationLogService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TransferService} 回归测试（P0-1 状态流转并发 + P2-6 创建/删除/编辑路径补齐）。
 *
 * <p>锁死契约：approve/complete 乐观锁冲突抛异常；仅 CREATED 可批准、仅 APPROVED 可完成；
 * 创建单号唯一校验 / requestId 幂等 / 撞键回查；删除条件删除（0 行即并发）、仅 CREATED 可删；
 * 编辑仅 CREATED 且乐观锁冲突抛异常。
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferMapper transferMapper;
    @Mock private TransferItemMapper transferItemMapper;
    @Mock private OperationLogService operationLogService;
    @Mock private DocNoSequenceService docNoSequenceService;
    @Spy @InjectMocks private TransferService transferService;

    @BeforeEach
    void setUp() {
        // @Spy @InjectMocks 的 ServiceImpl 不会自动注入继承字段 baseMapper，显式反射补上
        ReflectionTestUtils.setField(transferService, "baseMapper", transferMapper);
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private static TransferItem sampleItem() {
        TransferItem item = new TransferItem();
        item.setOrgId(1L);
        item.setProductName("铜管");
        item.setGrade("A");
        item.setQty(new BigDecimal("10"));
        item.setTargetLocation("A区");
        return item;
    }

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

    @Test
    void approve_状态非法_抛异常() {
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
        Transfer transfer = new Transfer();
        transfer.setId(104L);
        transfer.setTransferNo("TR-TEST-004");
        transfer.setStatus("APPROVED");
        transfer.setCreatedBy(2L);
        doReturn(transfer).when(transferService).getById(104L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transferService.approve(104L));
        assertTrue(ex.getMessage().contains("仅 CREATED 状态可批准"));
    }

    @Test
    void complete_状态非法_从APPROVED校验() {
        OperatorContext.set(new Operator(1L, "保管员", List.of("CHECKER")));
        Transfer transfer = new Transfer();
        transfer.setId(105L);
        transfer.setTransferNo("TR-TEST-005");
        transfer.setStatus("CREATED");
        transfer.setCreatedBy(2L);
        doReturn(transfer).when(transferService).getById(105L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transferService.complete(105L));
        assertTrue(ex.getMessage().contains("仅 APPROVED 状态可完成"));
    }

    // ===== 创建 =====

    @Test
    void create_幂等命中_返回已有不重复建() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        Transfer existed = new Transfer();
        existed.setId(900L);
        existed.setTransferNo("TR-IDEM-001");
        existed.setStatus("CREATED");
        doReturn(existed).when(transferService).getOne(any(), eq(false));
        doReturn(Collections.emptyList()).when(transferService).listItems(900L);

        TransferCreateRequest req = new TransferCreateRequest();
        req.setRequestId("req-abc");
        TransferDetailVO vo = transferService.create(req);

        assertEquals(900L, vo.getId(), "幂等命中应返回已有单据");
        verify(transferService, never()).save(any());
    }

    @Test
    void create_撞键_重查命中返回已有() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        Transfer existed = new Transfer();
        existed.setId(901L);
        existed.setTransferNo("TR-CK-001");
        existed.setStatus("CREATED");
        doReturn(null).doReturn(existed).when(transferService).getOne(any(), eq(false));
        doReturn(Collections.emptyList()).when(transferService).listItems(901L);
        doThrow(new DuplicateKeyException("uk_transfer_request_id")).when(transferService).save(any(Transfer.class));

        TransferCreateRequest req = new TransferCreateRequest();
        req.setRequestId("req-ck");
        req.setItems(List.of(sampleItem()));
        TransferDetailVO vo = transferService.create(req);

        assertEquals(901L, vo.getId(), "撞键后重查命中应返回已有单据");
    }

    @Test
    void create_单号已存在_抛400() {
        doReturn(1L).when(transferService).count(any());
        TransferCreateRequest req = new TransferCreateRequest();
        req.setTransferNo("TR-EXISTS");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> transferService.create(req));
        assertTrue(ex.getMessage().contains("调拨单号已存在"));
    }

    // ===== 删除 =====

    @Test
    void delete_条件删除零行_抛并发() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        Transfer head = new Transfer();
        head.setId(902L);
        head.setTransferNo("TR-DEL-001");
        head.setStatus("CREATED");
        head.setCreatedBy(1L);
        doReturn(head).when(transferService).getById(902L);
        doReturn(0).when(transferMapper).delete(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transferService.deleteWithItems(902L));
        assertTrue(ex.getMessage().contains("刷新重试"));
    }

    @Test
    void delete_非CREATED_抛异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        Transfer head = new Transfer();
        head.setId(902L);
        head.setTransferNo("TR-DEL-001");
        head.setStatus("APPROVED");
        head.setCreatedBy(1L);
        doReturn(head).when(transferService).getById(902L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transferService.deleteWithItems(902L));
        assertTrue(ex.getMessage().contains("仅 CREATED 状态可删除"));
    }

    // ===== 编辑 =====

    @Test
    void updateHead_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("CREATOR")));
        Transfer existed = new Transfer();
        existed.setId(903L);
        existed.setTransferNo("TR-UPD-001");
        existed.setStatus("CREATED");
        existed.setCreatedBy(1L);
        doReturn(existed).when(transferService).getById(903L);
        doReturn(false).when(transferService).updateById(any());
        TransferUpdateRequest req = new TransferUpdateRequest();
        req.setBatchNo("BATCH-9");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transferService.updateHead(903L, req));
        assertTrue(ex.getMessage().contains("并发冲突"));
    }
}
