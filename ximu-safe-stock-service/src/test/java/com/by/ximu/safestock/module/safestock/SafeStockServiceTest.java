package com.by.ximu.safestock.module.safestock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import com.by.ximu.common.PageQuery;
import com.by.ximu.common.web.audit.OperationLogService;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SafeStockService} 测试（R2-P1-11 safe-stock 测试地基）。
 *
 * <p>覆盖契约：
 * <ul>
 *   <li>查询侧：R2-P2-13 筛选参数过 {@code DimsNormalizer} 归一化——否则用户输入全角空格
 *       （如「Ａ　铜」）时匹配不到写入侧已归一化的数据，出现「刚创建查不到」；</li>
 *   <li>写路径：R2-P2-11 requestId trim 幂等 + 撞键转 400（「请勿重复创建」）；
 *       R2-P2-10 update 空串保持原值；R2-P2-14 delete 版本条件删除；
 *       R2-P2-12 审计记录生效值。</li>
 * </ul>
 *
 * <p>范式对齐 {@code InboundServiceTest}：@Mock Mapper + @Spy @InjectMocks Service +
 * {@code ReflectionTestUtils} 注入 baseMapper；写路径用例先 {@code OperatorContext.set}
 * 通过 {@code Auths.requireRole(Role.CHECKER, Role.ADMIN)}。
 */
@ExtendWith(MockitoExtension.class)
class SafeStockServiceTest {

    @Mock private SafeStockMapper safeStockMapper;
    @Mock private OperationLogService operationLogService;
    @Spy @InjectMocks private SafeStockService safeStockService;

    @BeforeAll
    static void initEntityLambdaCache() {
        // 纯 Mockito 环境无 MyBatis 启动流程，LambdaQueryWrapper 渲染列名依赖实体 TableInfo 缓存，
        // 手动初始化一次（幂等，不需要数据库连接）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SafeStock.class);
    }

    @BeforeEach
    void setUp() {
        // @Spy @InjectMocks 的 ServiceImpl 不会自动注入继承字段 baseMapper，显式反射补上
        ReflectionTestUtils.setField(safeStockService, "baseMapper", safeStockMapper);
    }

    @AfterEach
    void tearDown() {
        OperatorContext.clear();
    }

    private static SafeStock sample() {
        SafeStock s = new SafeStock();
        s.setId(1L);
        s.setProductName("铜管");
        s.setOrgId(1L);
        s.setVersion(0);
        return s;
    }

    private static SafeStockCreateRequest createReq(String requestId) {
        SafeStockCreateRequest req = new SafeStockCreateRequest();
        req.setProductName("铜管");
        req.setOrgId(1L);
        req.setRequestId(requestId);
        return req;
    }

    // ===== R2-P2-13：查询侧筛选过 DimsNormalizer =====

    /** R2-P2-13：productName/material 查询侧过 DimsNormalizer，全角空格归一化后仍能命中 */
    @Test
    void page_筛选参数过DimsNormalizer_归一化后构造查询() {
        doReturn(new Page<SafeStock>()).when(safeStockMapper).selectPage(any(), any());
        PageQuery query = new PageQuery();
        safeStockService.page(query, " Ａ　铜 ", " 紫铜 ");

        LambdaQueryWrapper<SafeStock> wrapper = capturedWrapper();
        // MP 3.5.12 条件值为延迟求值：formatParam 在 getCustomSqlSegment 渲染时才写入 paramNameValuePairs
        //（对齐 InboundServiceTest L198-201 注释范式）；like 值经 SqlUtils.concatLike 两侧拼 %（SqlLike.DEFAULT）
        String segment = wrapper.getCustomSqlSegment();
        assertTrue(segment.contains("product_name"), "productName 筛选条件应存在");
        assertTrue(segment.contains("material"), "material 筛选条件应存在");
        // 全角空格（U+3000）转半角后 trim：" Ａ　铜 " → "A 铜"；like 拼 % 后参数值为 "%A 铜%"——断言归一化结果
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%A 铜%"), "productName 应归一化后进查询");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%紫铜%"), "material 应归一化后进查询");
    }

    /** 未传筛选参数时不追加 like 条件（keyword 为空时不拼 and 条件） */
    @Test
    void page_不传筛选参数_不追加like条件() {
        doReturn(new Page<SafeStock>()).when(safeStockMapper).selectPage(any(), any());
        PageQuery query = new PageQuery();
        safeStockService.page(query, null, null);

        LambdaQueryWrapper<SafeStock> wrapper = capturedWrapper();
        // getCustomSqlSegment 触发渲染后再断言无 LIKE/无参数（对齐 InboundServiceTest 延迟求值范式）
        assertFalse(wrapper.getCustomSqlSegment().contains("LIKE"), "无筛选参数时不应有 LIKE 条件");
        assertTrue(wrapper.getParamNameValuePairs().isEmpty(), "无筛选参数时不应有查询参数");
    }

    // ===== R2-P2-11：requestId trim 后查重/落库 =====

    @Test
    void create_requestId_trim后落库() {
        OperatorContext.set(new Operator(1L, "操作员", List.of("CHECKER")));
        doReturn(1).when(safeStockMapper).insert(any(SafeStock.class));
        // findByIdempotent（getOne）默认返回 null → 走新建分支

        safeStockService.create(createReq("  REQ-001  "));

        ArgumentCaptor<SafeStock> captor = ArgumentCaptor.forClass(SafeStock.class);
        verify(safeStockMapper).insert(captor.capture());
        SafeStock saved = captor.getValue();
        assertEquals("REQ-001", saved.getRequestId(), "requestId 应 trim 后落库（R2-P2-11）");
        assertEquals("铜管", saved.getProductName(), "品名应经 DimsNormalizer 归一化后落库");
        // R2-P2-12：审计记录生效值（归一化后的 entity）而非原始请求体
        verify(operationLogService).recordInTx(eq("safe-stock"), eq("CREATE"), any(), eq("铜管"), eq("操作员"), same(saved));
    }

    @Test
    void create_幂等命中_返回已有配置不重复建() {
        OperatorContext.set(new Operator(1L, "操作员", List.of("CHECKER")));
        SafeStock existed = sample();
        doReturn(existed).when(safeStockService).getOne(any(), eq(false));

        SafeStock result = safeStockService.create(createReq("REQ-001"));

        assertEquals(existed, result, "幂等命中应返回已有配置（R2-P2-11）");
        verify(safeStockMapper, never()).insert(any(SafeStock.class));
    }

    @Test
    void create_并发撞键_幂等未命中抛400() {
        OperatorContext.set(new Operator(1L, "操作员", List.of("CHECKER")));
        doThrow(new DuplicateKeyException("uk_safe_stock_dims")).when(safeStockMapper).insert(any(SafeStock.class));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> safeStockService.create(createReq("REQ-001")));
        assertTrue(ex.getMessage().contains("请勿重复创建"), "撞键应转 400 文案（R2-P2-11）");
    }

    // ===== R2-P2-10：update 文本字段空串保持原值 =====

    @Test
    void update_文本字段空串_保持原值() {
        OperatorContext.set(new Operator(1L, "操作员", List.of("CHECKER")));
        SafeStock existed = sample();
        existed.setProductName("铜管");
        doReturn(existed).when(safeStockService).getById(1L);
        doReturn(true).when(safeStockService).updateById(any());

        SafeStock body = new SafeStock();
        body.setProductName("   ");                       // 空白串 → hasText false → 保持原值
        body.setSafeStock(new BigDecimal("15.5"));        // 非文本字段正常更新
        safeStockService.update(1L, body);

        assertEquals("铜管", existed.getProductName(), "空串不覆盖品名（R2-P2-10）");
        assertEquals(0, new BigDecimal("15.5").compareTo(existed.getSafeStock()), "非文本字段正常更新");
    }

    // ===== R2-P2-14：DELETE 带 version 条件删除 =====

    @Test
    void delete_version不匹配_抛并发冲突() {
        OperatorContext.set(new Operator(1L, "操作员", List.of("CHECKER")));
        SafeStock existed = sample();
        existed.setVersion(0);
        doReturn(existed).when(safeStockService).getById(1L);
        doReturn(0).when(safeStockMapper).delete(any());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> safeStockService.delete(1L, 99));
        assertTrue(ex.getMessage().contains("并发冲突，配置已被他人修改"), "版本不匹配应抛并发冲突（R2-P2-14）");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void delete_匹配_删除成功且条件删除带id与version() {
        OperatorContext.set(new Operator(1L, "操作员", List.of("CHECKER")));
        SafeStock existed = sample();
        existed.setVersion(0);
        doReturn(existed).when(safeStockService).getById(1L);
        doReturn(1).when(safeStockMapper).delete(any());

        assertDoesNotThrow(() -> safeStockService.delete(1L, 0));

        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(safeStockMapper).delete(captor.capture());
        String segment = captor.getValue().getSqlSegment();
        assertTrue(segment.contains("id"), "条件删除应带 id 条件（R2-P2-14）");
        assertTrue(segment.contains("version"), "条件删除应带 version 条件（R2-P2-14）");
    }

    // ===== P2-6：update 撞维度唯一键转业务 400 =====

    @Test
    void update_撞键_抛400() {
        OperatorContext.set(new Operator(1L, "操作员", List.of("CHECKER")));
        SafeStock existed = sample();
        doReturn(existed).when(safeStockService).getById(1L);
        // 改成已存在的维度组合撞 uk_safe_stock_dims，转成明确业务报错而非裸 500
        doThrow(new DuplicateKeyException("uk_safe_stock_dims")).when(safeStockService).updateById(any());

        SafeStock body = new SafeStock();
        body.setProductName("紫铜");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> safeStockService.update(1L, body));
        assertTrue(ex.getMessage().contains("无法改为此维度组合"), "撞键应转 400 固定文案（R2-P2-11）");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<SafeStock> capturedWrapper() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(safeStockMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }
}
