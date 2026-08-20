package com.by.ximu.common.web.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * {@link OperationLogService} 审计日志服务测试（R2-P1-11 safe-stock 测试地基，随模块收敛迁入 common-web）。
 *
 * <p>核心契约（P2-7）：事务内 {@link #recordInTx} 不吞异常（审计失败即回滚业务写，
 * 不再"改了数据、丢审计"）；非事务兜底 {@link #record} 吞异常（不阻断业务主流程）。
 */
@ExtendWith(MockitoExtension.class)
class OperationLogServiceTest {

    @Mock private ObjectMapper objectMapper;
    @Mock private OperationLogMapper operationLogMapper;
    @InjectMocks private OperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        // ServiceImpl 继承字段 baseMapper 由 Spring 注入，单元测试显式反射补上
        ReflectionTestUtils.setField(operationLogService, "baseMapper", operationLogMapper);
    }

    @Test
    void recordInTx_落库失败_异常外抛() {
        doThrow(new RuntimeException("db down")).when(operationLogMapper).insert(any(OperationLog.class));
        assertThrows(RuntimeException.class,
                () -> operationLogService.recordInTx("safe-stock", "CREATE", 1L, "铜管", "操作员", null));
    }

    @Test
    void record_落库失败_吞异常不阻断() {
        doThrow(new RuntimeException("db down")).when(operationLogMapper).insert(any(OperationLog.class));
        assertDoesNotThrow(
                () -> operationLogService.record("safe-stock", "CREATE", 1L, "铜管", "操作员", null));
    }

    @Test
    void recordInTx_detail序列化失败_抛IllegalStateException() throws Exception {
        // JsonProcessingException 构造器为 protected，用匿名子类实例化以触发 writeDetail 的序列化失败分支
        doThrow(new JsonProcessingException("bad") {}).when(objectMapper).writeValueAsString(any());
        assertThrows(IllegalStateException.class,
                () -> operationLogService.recordInTx("safe-stock", "CREATE", 1L, "铜管", "操作员", new Object()));
    }
}
