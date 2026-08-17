package com.by.ximu.inventory.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DocNoSequenceService#next(String)} 的纯单元测试（mock {@link JdbcTemplate}，不依赖 DB / Spring 上下文）。
 *
 * <p>通过 stub {@code JdbcTemplate#execute(ConnectionCallback)} 直接调用传入的回调，
 * 用 mock 的 Connection / PreparedStatement / ResultSet 走完 SQL 执行路径，
 * 验证序号格式化（不足 3 位补零、超过 3 位不截断）与异常向上传播。
 */
@ExtendWith(MockitoExtension.class)
class DocNoSequenceServiceTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DocNoSequenceService docNoSequenceService;

    private static String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    /** 让 mocked JdbcTemplate 把 execute 收到的回调直接作用于给定 Connection */
    private void stubExecuteInvokesCallback(Connection con) {
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<Long> callback = invocation.getArgument(0);
            return callback.doInConnection(con);
        });
    }

    @Test
    void next_序列值为1_补零为001() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(con.prepareStatement(anyString())).thenReturn(insertPs, selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(1L);
        stubExecuteInvokesCallback(con);

        String no = docNoSequenceService.next("IN");

        assertEquals("IN" + today() + "001", no);
        verify(insertPs).setString(1, "IN" + today());
        verify(insertPs).executeUpdate();
        verify(selectPs).executeQuery();
    }

    @Test
    void next_序列值为42_补零为042() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(con.prepareStatement(anyString())).thenReturn(insertPs, selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(42L);
        stubExecuteInvokesCallback(con);

        String no = docNoSequenceService.next("OUT");

        assertEquals("OUT" + today() + "042", no);
    }

    @Test
    void next_序列值为1234_超过三位不截断() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(con.prepareStatement(anyString())).thenReturn(insertPs, selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(1234L);
        stubExecuteInvokesCallback(con);

        String no = docNoSequenceService.next("CK");

        assertEquals("CK" + today() + "1234", no);
    }

    @Test
    void next_回调抛SQLException_向上传播() throws Exception {
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenThrow(new SQLException("模拟数据库异常"));
        stubExecuteInvokesCallback(con);

        assertThrows(SQLException.class, () -> docNoSequenceService.next("IN"));
    }

    @Test
    void next_结果集无记录_抛IllegalStateException() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement insertPs = mock(PreparedStatement.class);
        PreparedStatement selectPs = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        when(con.prepareStatement(anyString())).thenReturn(insertPs, selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        stubExecuteInvokesCallback(con);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> docNoSequenceService.next("IN"));

        assertTrue(ex.getMessage().contains("IN" + today()));
    }

    @Test
    void next_execute仅调用一次_返回值直接参与拼接() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(42L);

        String no = docNoSequenceService.next("TR");

        assertEquals("TR" + today() + "042", no);
        verify(jdbcTemplate, times(1)).execute(any(ConnectionCallback.class));
    }
}
