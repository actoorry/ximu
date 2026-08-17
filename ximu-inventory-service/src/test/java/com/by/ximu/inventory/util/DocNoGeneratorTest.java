package com.by.ximu.inventory.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocNoGenerator} 静态方法的纯逻辑单元测试。
 *
 * <p>不依赖 Spring 上下文与数据库，仅验证单号生成与序号解析的纯函数行为。
 */
class DocNoGeneratorTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 返回当天日期串（yyyyMMdd），用于断言 generate 的日期部分 */
    private static String today() {
        return LocalDate.now().format(DATE_FMT);
    }

    @Test
    void generate_fromOne_zeroPadded() {
        assertEquals("IN" + today() + "001", DocNoGenerator.generate("IN", 0L));
        assertEquals("IN" + today() + "002", DocNoGenerator.generate("IN", 1L));
        assertEquals("IN" + today() + "999", DocNoGenerator.generate("IN", 998L));
    }

    @Test
    void generate_nullMaxSeqAsZero() {
        assertEquals("IN" + today() + "001", DocNoGenerator.generate("IN", (Long) null));
    }

    @Test
    void generate_seqOver999_notTruncated() {
        assertEquals("IN" + today() + "1000", DocNoGenerator.generate("IN", 999L));
        assertEquals("IN" + today() + "1000000", DocNoGenerator.generate("IN", 999999L));
    }

    @Test
    void generate_differentPrefixes() {
        assertTrue(DocNoGenerator.generate("OUT", 4L).startsWith("OUT" + today()));
        assertTrue(DocNoGenerator.generate("CK", 4L).startsWith("CK" + today()));
        assertTrue(DocNoGenerator.generate("TR", 4L).startsWith("TR" + today()));
    }

    @Test
    void maxSeqOf_emptyReturnsZero() {
        assertEquals(0L, DocNoGenerator.maxSeqOf(null, 2));
        assertEquals(0L, DocNoGenerator.maxSeqOf(Collections.emptyList(), 2));
    }

    @Test
    void maxSeqOf_parsesMaxSeq() {
        List<String> nos = Arrays.asList("IN20260814001", "IN20260814010", "IN20260814005");
        assertEquals(10L, DocNoGenerator.maxSeqOf(nos, 2));
    }

    @Test
    void maxSeqOf_skipsInvalidFormats() {
        List<String> nos = new ArrayList<>();
        nos.add("IN20260814001");
        nos.add(null);
        nos.add("IN20260814");
        nos.add("IN20260814abc");
        assertEquals(1L, DocNoGenerator.maxSeqOf(nos, 2));
    }

    @Test
    void maxSeqOf_usesPrefixLength() {
        assertEquals(123L, DocNoGenerator.maxSeqOf(Collections.singletonList("OUT20260814123"), 3));
    }
}
