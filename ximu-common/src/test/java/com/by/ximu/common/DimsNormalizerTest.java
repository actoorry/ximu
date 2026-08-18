package com.by.ximu.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DimsNormalizer} 的纯单元测试：null 透传、半角不变、全角转半角、
 * 首尾空白（含全角空格）trim、内部空格保留、中文不受影响、大小写不归一。
 */
class DimsNormalizerTest {

    @Test
    void null原样返回_不吞缺省语义() {
        assertNull(DimsNormalizer.normalize(null));
    }

    @Test
    void 纯半角_原样返回() {
        assertEquals("苹果", DimsNormalizer.normalize("苹果"));
        assertEquals("T2铜管", DimsNormalizer.normalize("T2铜管"));
        assertEquals("", DimsNormalizer.normalize(""));
    }

    @Test
    void 首尾半角空格_trim() {
        assertEquals("铜管", DimsNormalizer.normalize("  铜管 "));
    }

    @Test
    void 首尾全角空格_转半角后trim() {
        assertEquals("铜管", DimsNormalizer.normalize("　铜管　"));
        assertEquals("铜管", DimsNormalizer.normalize("　 铜管 　 "));
    }

    @Test
    void 全角字母数字_转半角() {
        assertEquals("ABC123", DimsNormalizer.normalize("ＡＢＣ１２３"));
        assertEquals("T2", DimsNormalizer.normalize("Ｔ２"));
    }

    @Test
    void 全角ASCII符号_转半角() {
        assertEquals("99.99%", DimsNormalizer.normalize("９９．９９％"));
        assertEquals("[A]!", DimsNormalizer.normalize("［Ａ］！"));
        assertEquals("~", DimsNormalizer.normalize("～"));
    }

    @Test
    void 中文汉字_CJK专用标点_不受影响() {
        // 中文汉字与 CJK 标点区（、U+3001、。U+3002）不在全角 ASCII 区间，原样保留
        assertEquals("铜管、一级。", DimsNormalizer.normalize("铜管、一级。"));
        assertEquals("一级品", DimsNormalizer.normalize("一级品"));
    }

    @Test
    void 全角形式ASCII标点_转半角() {
        // 全角逗号 U+FF0C 落在 U+FF01~U+FF5E 全角 ASCII 区内 → 转半角逗号（设计内行为）
        assertEquals("铜管,一级", DimsNormalizer.normalize("铜管，一级"));
        assertEquals("铜管:一级;", DimsNormalizer.normalize("铜管：一级；"));
    }

    @Test
    void 内部空格_保留不折叠() {
        assertEquals("铜 管", DimsNormalizer.normalize(" 铜 管 "));
        // 全角空格在中间也转半角，但不删除
        assertEquals("铜 管", DimsNormalizer.normalize("铜　管"));
    }

    @Test
    void 大小写_不归一() {
        // DB collation 已不区分大小写，Java 侧保留原样避免改动落库显示值
        assertEquals("ABC", DimsNormalizer.normalize("ABC"));
        assertEquals("abc", DimsNormalizer.normalize("abc"));
    }

    @Test
    void 混合场景_全角数字加首尾空白() {
        assertEquals("Φ20mm管123", DimsNormalizer.normalize("　Φ２０ｍｍ管１２３　"));
    }
}
