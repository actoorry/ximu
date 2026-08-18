package com.by.ximu.inventory.module.inbound;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.inventory.module.log.OperationLogService;
import com.by.ximu.inventory.module.stock.StockOperationService;
import com.by.ximu.inventory.util.DocNoSequenceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link InboundService} P0 回归测试：
 * P0-1 审核人取可信登录人（不读请求体）、approve 不覆盖审核级别、无操作人拒绝落库；
 * P0-1(旧) 乐观锁冲突抛异常；P0-3 删除走条件删除（0 行即并发异常，不再无条件 removeById）。
 * 其余三个单据 Service 的流转/删除为同款复制模式，以本类为代表锁定行为。
 */
@ExtendWith(MockitoExtension.class)
class InboundServiceTest {

    @Mock private InboundMapper inboundMapper;
    @Mock private InboundItemMapper inboundItemMapper;
    @Mock private StockOperationService stockOperationService;
    @Mock private OperationLogService operationLogService;
    @Mock private DocNoSequenceService docNoSequenceService;
    @Spy @InjectMocks private InboundService inboundService;

    @BeforeAll
    static void initEntityLambdaCache() {
        // 纯 Mockito 环境无 MyBatis 启动流程，LambdaQueryWrapper 渲染列名依赖实体 TableInfo 缓存，
        // 手动初始化一次（幂等，重复调用直接命中缓存；不需要数据库连接）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Inbound.class);
    }

    @BeforeEach
    void setUp() {
        // Boot 3.5.16（Mockito 5.17+）不再向 @Spy @InjectMocks 的 ServiceImpl 注入继承字段 baseMapper，
        // 显式反射补上（生产环境由 Spring 容器正常注入，不受影响）
        ReflectionTestUtils.setField(inboundService, "baseMapper", inboundMapper);
    }

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
                () -> inboundService.approve(100L));
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
                () -> inboundService.check(101L));
        assertTrue(ex.getMessage().contains("单据已被他人操作"));
    }

    // ===== P0-1：审核人身份不信任请求体 =====

    @Test
    void check_审核人取登录人_不再读请求体() {
        OperatorContext.set(new Operator(1L, "真实保管员", List.of("CHECKER")));
        Inbound inbound = approvedInbound(104L, "IN-TEST-004");
        doReturn(inbound).when(inboundService).getById(104L);
        doReturn(true).when(inboundService).updateById(any());
        doReturn(Collections.emptyList()).when(inboundService).listItems(104L);
        inboundService.check(104L);
        assertEquals("真实保管员", inbound.getChecker());
    }

    @Test
    void approve_不再接受请求体_审核级别保持制单人设定() {
        OperatorContext.set(new Operator(1L, "审批人", List.of("APPROVER")));
        Inbound inbound = new Inbound();
        inbound.setId(105L);
        inbound.setInboundNo("IN-TEST-005");
        inbound.setStatus("CREATED");
        inbound.setCreatedBy(2L);
        inbound.setAuditLevel("经理审核");
        doReturn(inbound).when(inboundService).getById(105L);
        doReturn(true).when(inboundService).updateById(any());
        inboundService.approve(105L);
        assertEquals("APPROVED", inbound.getStatus());
        assertEquals("经理审核", inbound.getAuditLevel());
    }

    @Test
    void check_操作人姓名为空_拒绝落库() {
        // X-User-Name 头为空串可达此路径（过滤器只强校验 X-User-Id），宁可拒绝也不写空审核人
        OperatorContext.set(new Operator(1L, "", List.of("CHECKER")));
        Inbound inbound = approvedInbound(106L, "IN-TEST-006");
        doReturn(inbound).when(inboundService).getById(106L);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inboundService.check(106L));
        assertTrue(ex.getMessage().contains("操作人上下文缺失"));
    }

    // ===== P0-3：删除走条件删除 =====

    @Test
    void delete_条件删除零行_抛并发异常() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        Inbound inbound = new Inbound();
        inbound.setId(102L);
        inbound.setInboundNo("IN-TEST-003");
        inbound.setStatus("CREATED");
        inbound.setCreatedBy(1L);
        doReturn(inbound).when(inboundService).getById(102L);
        // 模拟竞态：读到 CREATED 后单据已被并发流转，条件删除（id+status）匹配 0 行
        doReturn(0).when(inboundMapper).delete(any());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> inboundService.deleteWithItems(102L));
        assertTrue(ex.getMessage().contains("刷新重试"));
    }

    @Test
    void delete_条件删头成功_再删明细并记审计() {
        OperatorContext.set(new Operator(1L, "制单人", List.of("ADMIN")));
        Inbound inbound = new Inbound();
        inbound.setId(103L);
        inbound.setInboundNo("IN-TEST-007");
        inbound.setStatus("CREATED");
        inbound.setCreatedBy(1L);
        doReturn(inbound).when(inboundService).getById(103L);
        doReturn(1).when(inboundMapper).delete(any());
        assertDoesNotThrow(() -> inboundService.deleteWithItems(103L));
        verify(inboundItemMapper).delete(any());
        verify(operationLogService).recordInTx(eq("inbound"), eq("DELETE"), eq(103L), eq("IN-TEST-007"), any(), any());
    }

    // ===== P1-7：幂等回查限定「requestId + 当前操作人」复合键 =====

    @Test
    void create_幂等命中_回查限定当前操作人且不再建单() {
        OperatorContext.set(new Operator(7L, "制单人", List.of("CREATOR")));
        Inbound existed = new Inbound();
        existed.setId(900L);
        existed.setInboundNo("IN-IDEM-001");
        existed.setStatus("CREATED");
        doReturn(existed).when(inboundService).getOne(any(), eq(false));
        doReturn(Collections.emptyList()).when(inboundService).listItems(900L);

        InboundCreateRequest req = new InboundCreateRequest();
        req.setRequestId("req-abc");
        InboundDetailVO vo = inboundService.create(req);

        assertEquals(900L, vo.getId());
        // 回查条件必须同时含 requestId 与当前操作人 ID：防止回归为全局 requestId 查询（跨用户串单）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Inbound>> captor =
                ArgumentCaptor.forClass((Class<LambdaQueryWrapper<Inbound>>) (Class<?>) LambdaQueryWrapper.class);
        verify(inboundService).getOne(captor.capture(), eq(false));
        // MP 3.5.12 条件值为延迟求值：formatParam 在 SQL segment 渲染时才写入 paramNameValuePairs；
        // 单测中 getOne 被 stub 不会真的渲染，这里先触发一次渲染再取参数
        LambdaQueryWrapper<Inbound> idempotentQuery = captor.getValue();
        String sqlSegment = idempotentQuery.getCustomSqlSegment();
        assertTrue(sqlSegment.contains("request_id"), "幂等回查应按 request_id 列过滤");
        assertTrue(sqlSegment.contains("created_by"), "幂等回查应按 created_by 列过滤（P1-7 复合幂等键）");
        Collection<Object> params = idempotentQuery.getParamNameValuePairs().values();
        assertTrue(params.contains("req-abc"), "幂等回查条件应包含 requestId");
        assertTrue(params.contains(7L), "幂等回查条件应包含当前操作人 ID（P1-7 复合幂等键）");
        verify(inboundService, never()).save(any());
    }

    private Inbound approvedInbound(long id, String docNo) {
        Inbound inbound = new Inbound();
        inbound.setId(id);
        inbound.setInboundNo(docNo);
        inbound.setStatus("APPROVED");
        inbound.setCreatedBy(2L);
        return inbound;
    }
}
