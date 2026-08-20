package com.by.ximu.inventory.module.outbound;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.web.audit.OperationLogService;
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OutboundService} 回归测试（P0-1 状态流转并发 + P2-6 创建/删除/编辑路径补齐）。
 *
 * <p>锁死契约：approve 乐观锁冲突抛异常防并发超卖；approve 按明细逐行扣减库存（五维键排序防交叉死锁）；
 * 创建单号唯一校验 / requestId 复合幂等 / 撞键回查两次仍未中抛并发；删除条件删除（0 行即并发）；
 * 编辑仅 CREATED 且乐观锁冲突抛异常。
 */
@ExtendWith(MockitoExtension.class)
class OutboundServiceTest {

    @Mock private OutboundMapper outboundMapper;
    @Mock private OutboundItemMapper outboundItemMapper;
    @Mock private StockOperationService stockOperationService;
    @Mock private OperationLogService operationLogService;
    @Mock private DocNoSequenceService docNoSequenceService;
    @Spy @InjectMocks private OutboundService outboundService;

    @BeforeAll
    static void initEntityLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Outbound.class);
    }

    @BeforeEach
    void setUp() {
        // @Spy @InjectMocks 的 ServiceImpl 不会自动注入继承字段 baseMapper，显式反射补上
        ReflectionTestUtils.setField(outboundService, "baseMapper", outboundMapper);
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private static Outbound createdOutbound(long id, String docNo, Long createdBy) {
        Outbound outbound = new Outbound();
        outbound.setId(id);
        outbound.setOutboundNo(docNo);
        outbound.setStatus("CREATED");
        outbound.setCreatedBy(createdBy);
        return outbound;
    }

    private static OutboundItem sampleItem() {
        OutboundItem item = new OutboundItem();
        item.setOrgId(1L);
        item.setProductName("铜管");
        item.setGrade("A");
        item.setQty(new BigDecimal("10"));
        return item;
    }

    @Test
    void approve_乐观锁冲突_抛异常防并发超卖() {
        Outbound outbound = createdOutbound(100L, "OUT-TEST-001", 2L);
        doReturn(outbound).when(outboundService).getById(100L);
        doReturn(false).when(outboundService).updateById(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.approve(100L));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }

    @Test
    void approve_状态非法_抛异常() {
        Outbound outbound = createdOutbound(100L, "OUT-TEST-001", 2L);
        outbound.setStatus("APPROVED");
        doReturn(outbound).when(outboundService).getById(100L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.approve(100L));
        assertTrue(ex.getMessage().contains("仅 CREATED 状态可批准"));
    }

    @Test
    void approve_库存联动_按明细逐行扣减() {
        Outbound outbound = createdOutbound(100L, "OUT-TEST-001", 2L);
        doReturn(outbound).when(outboundService).getById(100L);
        doReturn(true).when(outboundService).updateById(any());
        // 输入乱序：铝管在前、铜管在后；dimsKey 排序后铜管(U+94DC) 应在铝管(U+94DD) 前
        OutboundItem copper = new OutboundItem();
        copper.setOrgId(1L);
        copper.setProductName("铜管");
        copper.setGrade("A");
        copper.setQty(new BigDecimal("2"));
        OutboundItem aluminum = new OutboundItem();
        aluminum.setOrgId(1L);
        aluminum.setProductName("铝管");
        aluminum.setGrade("B");
        aluminum.setQty(new BigDecimal("3"));
        // approve 内会对明细 in-place sort（P1-3 死锁消解），必须是可变列表；List.of 不可变会抛 UOE
        doReturn(new ArrayList<>(List.of(aluminum, copper))).when(outboundService).listItems(100L);

        outboundService.approve(100L);

        InOrder inOrder = inOrder(stockOperationService);
        inOrder.verify(stockOperationService).decreaseStock(eq(1L), eq("A"), eq("铜管"), isNull(), isNull(), eq(new BigDecimal("2")));
        inOrder.verify(stockOperationService).decreaseStock(eq(1L), eq("B"), eq("铝管"), isNull(), isNull(), eq(new BigDecimal("3")));
    }

    @Test
    void approve_库存不足_异常外抛() {
        Outbound outbound = createdOutbound(100L, "OUT-TEST-001", 2L);
        doReturn(outbound).when(outboundService).getById(100L);
        doReturn(true).when(outboundService).updateById(any());
        // approve 内会对明细 in-place sort，必须可变列表
        doReturn(new ArrayList<>(List.of(sampleItem()))).when(outboundService).listItems(100L);
        doThrow(new IllegalStateException("库存不足: 铜管，当前库存 1，需出库 10"))
                .when(stockOperationService).decreaseStock(any(), any(), any(), any(), any(), any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.approve(100L));
        assertTrue(ex.getMessage().contains("库存不足"));
    }

    // ===== 创建 =====

    @Test
    void create_幂等命中_返回已有不重复建() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        Outbound existed = createdOutbound(900L, "OUT-IDEM-001", 7L);
        doReturn(existed).when(outboundService).getOne(any(), eq(false));
        doReturn(Collections.emptyList()).when(outboundService).listItems(900L);

        OutboundCreateRequest req = new OutboundCreateRequest();
        req.setRequestId("req-abc");
        OutboundDetailVO vo = outboundService.create(req);

        assertEquals(900L, vo.getId(), "幂等命中应返回已有单据");
        verify(outboundService, never()).save(any());
    }

    @Test
    void create_撞键_重查命中返回已有() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        Outbound existed = createdOutbound(901L, "OUT-CK-001", 7L);
        // 第一次幂等回查未命中（null），撞键后重查命中（existed）→ 返回已有
        doReturn(null).doReturn(existed).when(outboundService).getOne(any(), eq(false));
        doReturn(Collections.emptyList()).when(outboundService).listItems(901L);
        doThrow(new DuplicateKeyException("uk_outbound_request_id")).when(outboundService).save(any(Outbound.class));

        OutboundCreateRequest req = new OutboundCreateRequest();
        req.setRequestId("req-ck");
        req.setItems(List.of(sampleItem()));
        OutboundDetailVO vo = outboundService.create(req);

        assertEquals(901L, vo.getId(), "撞键后重查命中应返回已有单据");
    }

    @Test
    void create_撞键_重查两次未中_抛并发重复() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        // 幂等回查、撞键后立即重查、sleep 后重查三次均未命中 → 并发冲突
        doReturn(null, null, null).when(outboundService).getOne(any(), eq(false));
        doThrow(new DuplicateKeyException("uk_outbound_request_id")).when(outboundService).save(any(Outbound.class));

        OutboundCreateRequest req = new OutboundCreateRequest();
        req.setRequestId("req-dup");
        req.setItems(List.of(sampleItem()));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.create(req));
        assertTrue(ex.getMessage().contains("并发重复请求"));
    }

    @Test
    void create_单号已存在_抛400() {
        doReturn(1L).when(outboundService).count(any());
        OutboundCreateRequest req = new OutboundCreateRequest();
        req.setOutboundNo("OUT-EXISTS");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> outboundService.create(req));
        assertTrue(ex.getMessage().contains("出库单号已存在"));
    }

    @Test
    void create_明细为空_抛400() {
        doReturn("OUT20260819001").when(docNoSequenceService).next("OUT");
        OutboundCreateRequest req = new OutboundCreateRequest();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> outboundService.create(req));
        assertTrue(ex.getMessage().contains("出库明细不能为空"));
    }

    @Test
    void create_单号自动生成() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        doReturn("OUT20260819001").when(docNoSequenceService).next("OUT");
        OutboundCreateRequest req = new OutboundCreateRequest();
        req.setItems(List.of(sampleItem()));

        OutboundDetailVO vo = outboundService.create(req);

        verify(docNoSequenceService).next("OUT");
        assertEquals("OUT20260819001", vo.getOutboundNo(), "未传单号时应走单号自动生成");
    }

    // ===== 删除 =====

    @Test
    void delete_条件删除零行_抛并发() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        Outbound outbound = createdOutbound(102L, "OUT-DEL-001", 1L);
        doReturn(outbound).when(outboundService).getById(102L);
        doReturn(0).when(outboundMapper).delete(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.deleteWithItems(102L));
        assertTrue(ex.getMessage().contains("刷新重试"));
    }

    @Test
    void delete_删头成功_删明细并记审计() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        Outbound outbound = createdOutbound(102L, "OUT-DEL-001", 1L);
        doReturn(outbound).when(outboundService).getById(102L);
        doReturn(1).when(outboundMapper).delete(any());
        assertDoesNotThrow(() -> outboundService.deleteWithItems(102L));
        verify(outboundItemMapper).delete(any());
        verify(operationLogService).recordInTx(eq("outbound"), eq("DELETE"), eq(102L), eq("OUT-DEL-001"), eq("制单人"), any());
    }

    @Test
    void delete_非CREATED_抛异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        Outbound outbound = createdOutbound(102L, "OUT-DEL-001", 1L);
        outbound.setStatus("APPROVED");
        doReturn(outbound).when(outboundService).getById(102L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.deleteWithItems(102L));
        assertTrue(ex.getMessage().contains("仅 CREATED 状态可删除"));
    }

    // ===== 编辑 =====

    @Test
    void updateHead_乐观锁冲突_抛异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("CREATOR")));
        Outbound existed = createdOutbound(103L, "OUT-UPD-001", 1L);
        doReturn(existed).when(outboundService).getById(103L);
        doReturn(false).when(outboundService).updateById(any());
        OutboundUpdateRequest req = new OutboundUpdateRequest();
        req.setSaleOrderNo("SO-123");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.updateHead(103L, req));
        assertTrue(ex.getMessage().contains("并发冲突"));
    }

    @Test
    void updateHead_部分更新_仅改非null() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("CREATOR")));
        Outbound existed = createdOutbound(103L, "OUT-UPD-001", 1L);
        doReturn(existed).when(outboundService).getById(103L);
        doReturn(true).when(outboundService).updateById(any());
        OutboundUpdateRequest req = new OutboundUpdateRequest();
        req.setSaleOrderNo("SO-NEW");
        outboundService.updateHead(103L, req);
        assertEquals("SO-NEW", existed.getSaleOrderNo(), "传入非 null 字段应更新");
        assertNull(existed.getFreightBearer(), "未传字段应保持原值（部分更新语义）");
        verify(operationLogService).recordInTx(eq("outbound"), eq("UPDATE"), eq(103L), eq("OUT-UPD-001"), eq("制单人"), eq(req));
    }

    @Test
    void updateHead_非CREATED_抛异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("CREATOR")));
        Outbound existed = createdOutbound(103L, "OUT-UPD-001", 1L);
        existed.setStatus("APPROVED");
        doReturn(existed).when(outboundService).getById(103L);
        OutboundUpdateRequest req = new OutboundUpdateRequest();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> outboundService.updateHead(103L, req));
        assertTrue(ex.getMessage().contains("仅 CREATED 状态可编辑"));
    }
}
