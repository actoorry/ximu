package com.by.ximu.safestock.module.safestock;

import com.by.ximu.common.Operator;
import com.by.ximu.common.OperatorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;

/**
 * {@link SafeStockController} 薄层委托测试（R2-P1-11 safe-stock 测试地基）。
 *
 * <p>Controller 写路径业务逻辑（幂等回查、撞键转 400、白名单映射、条件删除+乐观锁、审计）
 * 已下沉到 {@link SafeStockService}，对应契约测试见 {@link SafeStockServiceTest}。
 * 本测试仅锁定薄层委托行为：get 不存在抛 {@link NoSuchElementException}（404）、get 存在返回配置。
 */
@ExtendWith(MockitoExtension.class)
class SafeStockControllerTest {

    @Mock private SafeStockService safeStockService;
    @InjectMocks private SafeStockController controller;

    @BeforeEach
    void setUp() {
        // Controller.get 首行 Auths.requireRole(VIEWER, ADMIN)，无角色上下文直接抛 ForbiddenException
        //（对齐 ServiceTest 范式，见 SafeStockServiceTest 写路径用例）
        OperatorContext.set(new Operator(1L, "操作员", List.of("VIEWER")));
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

    // ===== R2-P2-9 / R2-P2-16：get 不存在 → 404（NoSuchElementException） =====

    @Test
    void get_不存在_抛NoSuchElementException() {
        doReturn(null).when(safeStockService).getById(999L);
        assertThrows(NoSuchElementException.class, () -> controller.get(999L));
    }

    @Test
    void get_存在_返回配置() {
        SafeStock s = sample();
        doReturn(s).when(safeStockService).getById(1L);
        assertEquals(s, controller.get(1L).getData());
    }
}
