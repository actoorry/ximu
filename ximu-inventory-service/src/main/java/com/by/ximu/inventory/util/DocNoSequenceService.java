package com.by.ximu.inventory.util;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 单据号原子取号服务：基于 MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE} + {@code LAST_INSERT_ID()}
 * 在数据库侧完成「当天序号 +1」的原子自增，替代原先单机 {@code synchronized} 内「查当天最大序号 +1」的方案，
 * 从而保证多实例部署下同一天同类单据的序号不重复（单号唯一索引兜底）。
 *
 * <p>关键点：{@code LAST_INSERT_ID()} 是连接级状态，因此「原子自增」与「读取 LAST_INSERT_ID()」
 * 必须发生在同一个 {@link java.sql.Connection} 上。这里通过 {@link JdbcTemplate#execute(ConnectionCallback)}
 * 在单个回调里完成 INSERT 与 SELECT，保证同连接。回调内部经 Spring {@code DataSourceUtils} 取连接，
 * 在已有事务中会复用事务连接，使序号写入与单据落库同事务、同连接。
 */
@Service
@RequiredArgsConstructor
public class DocNoSequenceService {

    /** 单号日期格式：yyyyMMdd */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 原子取号 SQL：
     * <ul>
     *   <li>首次插入（无冲突）：{@code LAST_INSERT_ID(1)} 同时写入 seq=1 并设置连接级 LAST_INSERT_ID=1；</li>
     *   <li>命中唯一键（已有当天序列）：{@code seq = LAST_INSERT_ID(seq + 1)} 自增并返回新值。</li>
     * </ul>
     * 两条分支都保证连接级 {@code LAST_INSERT_ID()} 等于本连接本次取到的序号，避免「首次插入返回 0」的坑。
     */
    private static final String NEXT_SEQ_SQL =
            "INSERT INTO doc_no_seq (seq_key, seq) VALUES (?, LAST_INSERT_ID(1)) "
                    + "ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1)";

    private static final String SELECT_LAST_INSERT_ID_SQL = "SELECT LAST_INSERT_ID()";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 生成单据号：{@code prefix + yyyyMMdd + 序号}，序号从 1 开始、至少 3 位（超过 999 不截断）。
     *
     * <p>{@code synchronized} 仅作单机串行优化；多实例原子性由数据库唯一键 + 原子自增保证。
     *
     * @param prefix 单据前缀（IN/OUT/CK/TR）
     * @return 形如 {@code IN20260814001} 的单号
     */
    public synchronized String next(String prefix) {
        String date = LocalDate.now().format(DATE_FMT);
        String seqKey = prefix + date;
        Long seq = jdbcTemplate.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement insert = con.prepareStatement(NEXT_SEQ_SQL)) {
                insert.setString(1, seqKey);
                insert.executeUpdate();
            }
            try (PreparedStatement select = con.prepareStatement(SELECT_LAST_INSERT_ID_SQL);
                 ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("获取单号序列失败: " + seqKey);
                }
                return rs.getLong(1);
            }
        });
        long seqNo = (seq == null ? 0L : seq);
        return prefix + date + String.format("%03d", seqNo);
    }
}
