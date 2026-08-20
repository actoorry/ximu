package com.by.ximu.inventory.module.check;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.web.audit.OperationLogService;
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
 * {@link InventoryCheckService} 回归测试（P0-1 状态流转并发 + P2-6 创建/删除/编辑路径补齐）。
 *
 * <p>锁死契约：approve/check 乐观锁冲突抛异常；仅 CREATED 可批准、仅 APPROVED 可审核；
 * 审核人取可信登录上下文（审计记录登录人）；创建单号唯一校验 / requestId 幂等 / 撞键回查；
 * 删除条件删除（0 行即并发）、仅 CREATED 可删；编辑仅 CREATED 且乐观锁冲突抛异常。
 */
@ExtendWith(MockitoExtension.class)
class InventoryCheckServiceTest {

    @Mock private InventoryCheckMapper inventoryCheckMapper;
    @Mock private CheckItemMapper checkItemMapper;
    @Mock private StockOperationService stockOperationService;
    @Mock private OperationLogService operationLogService;
    @Mock private DocNoSequenceService docNoSequenceService;
    @Spy @InjectMocks private InventoryCheckService inventoryCheckService;

    @BeforeEach
    void setUp() {
        // @Spy @InjectMocks 的 ServiceImpl 不会自动注入继承字段 baseMapper，显式反射补上
        ReflectionTestUtils.setField(inventoryCheckService, "baseMapper", inventoryCheckMapper);
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private static CheckItem sampleItem() {
        CheckItem item = new CheckItem();
        item.setOrgId(1L);
        item.setProductName("铜管");
        item.setGrade("A");
        item.setBookQty(new BigDecimal("10"));
        item.setActualQty(new BigDecimal("9"));
        return item;
    }

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

    @Test
    void approve_状态非法_抛异常() {
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
        InventoryCheck check = new InventoryCheck();
        check.setId(104L);
        check.setCheckNo("CK-TEST-004");
        check.setStatus("APPROVED");
        check.setCreatedBy(2L);
        doReturn(check).when(inventoryCheckService).getById(104L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inventoryCheckService.approve(104L));
        assertTrue(ex.getMessage().contains("仅 CREATED 状态可批准"));
    }

    @Test
    void check_状态非法_抛异常() {
        OperatorContext.set(new Operator(1L, "保管员", List.of("CHECKER")));
        InventoryCheck check = new InventoryCheck();
        check.setId(105L);
        check.setCheckNo("CK-TEST-005");
        check.setStatus("CREATED");
        check.setCreatedBy(2L);
        doReturn(check).when(inventoryCheckService).getById(105L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inventoryCheckService.check(105L));
        assertTrue(ex.getMessage().contains("仅 APPROVED 状态可审核"));
    }

    @Test
    void check_审核人取登录上下文_审计记录登录人() {
        OperatorContext.set(new Operator(1L, "真实保管员", List.of("CHECKER")));
        InventoryCheck check = new InventoryCheck();
        check.setId(107L);
        check.setCheckNo("CK-TEST-007");
        check.setStatus("APPROVED");
        check.setCreatedBy(2L);
        doReturn(check).when(inventoryCheckService).getById(107L);
        doReturn(Collections.emptyList()).when(inventoryCheckService).listItems(107L);
        doReturn(true).when(inventoryCheckService).updateById(any());

        inventoryCheckService.check(107L);

        // 盘点单头无审核人字段，审核人身份落在审计记录的 operator（取自可信登录上下文，不读请求体）
        verify(operationLogService).recordInTx(eq("check"), eq("CHECK"), eq(107L), eq("CK-TEST-007"), eq("真实保管员"), any());
    }

    // ===== 创建 =====

    @Test
    void create_幂等命中_返回已有不重复建() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        InventoryCheck existed = new InventoryCheck();
        existed.setId(900L);
        existed.setCheckNo("CK-IDEM-001");
        existed.setStatus("CREATED");
        doReturn(existed).when(inventoryCheckService).getOne(any(), eq(false));
        doReturn(Collections.emptyList()).when(inventoryCheckService).listItems(900L);

        CheckCreateRequest req = new CheckCreateRequest();
        req.setRequestId("req-abc");
        CheckDetailVO vo = inventoryCheckService.create(req);

        assertEquals(900L, vo.getId(), "幂等命中应返回已有单据");
        verify(inventoryCheckService, never()).save(any());
    }

    @Test
    void create_撞键_重查命中返回已有() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        InventoryCheck existed = new InventoryCheck();
        existed.setId(901L);
        existed.setCheckNo("CK-CK-001");
        existed.setStatus("CREATED");
        doReturn(null).doReturn(existed).when(inventoryCheckService).getOne(any(), eq(false));
        doReturn(Collections.emptyList()).when(inventoryCheckService).listItems(901L);
        doThrow(new DuplicateKeyException("uk_inventory_check_request_id")).when(inventoryCheckService).save(any(InventoryCheck.class));

        CheckCreateRequest req = new CheckCreateRequest();
        req.setRequestId("req-ck");
        req.setItems(List.of(sampleItem()));
        CheckDetailVO vo = inventoryCheckService.create(req);

        assertEquals(901L, vo.getId(), "撞键后重查命中应返回已有单据");
    }

    @Test
    void create_单号已存在_抛400() {
        doReturn(1L).when(inventoryCheckService).count(any());
        CheckCreateRequest req = new CheckCreateRequest();
        req.setCheckNo("CK-EXISTS");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> inventoryCheckService.create(req));
        assertTrue(ex.getMessage().contains("盘点单号已存在"));
    }

    // ===== 删除 =====

    @Test
    void delete_条件删除零行_抛并发() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        InventoryCheck head = new InventoryCheck();
        head.setId(902L);
        head.setCheckNo("CK-DEL-001");
        head.setStatus("CREATED");
        head.setCreatedBy(1L);
        doReturn(head).when(inventoryCheckService).getById(902L);
        doReturn(0).when(inventoryCheckMapper).delete(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inventoryCheckService.deleteWithItems(902L));
        assertTrue(ex.getMessage().contains("刷新重试"));
    }

    @Test
    void delete_非CREATED_抛异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        InventoryCheck head = new InventoryCheck();
        head.setId(902L);
        head.setCheckNo("CK-DEL-001");
        head.setStatus("APPROVED");
        head.setCreatedBy(1L);
        doReturn(head).when(inventoryCheckService).getById(902L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inventoryCheckService.deleteWithItems(902L));
        assertTrue(ex.getMessage().contains("仅 CREATED 状态可删除"));
    }

    // ===== 编辑 =====

    @Test
    void updateHead_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("CREATOR")));
        InventoryCheck existed = new InventoryCheck();
        existed.setId(903L);
        existed.setCheckNo("CK-UPD-001");
        existed.setStatus("CREATED");
        existed.setCreatedBy(1L);
        doReturn(existed).when(inventoryCheckService).getById(903L);
        doReturn(false).when(inventoryCheckService).updateById(any());
        CheckUpdateRequest req = new CheckUpdateRequest();
        req.setBatchNo("BATCH-9");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inventoryCheckService.updateHead(903L, req));
        assertTrue(ex.getMessage().contains("并发冲突"));
    }
}
