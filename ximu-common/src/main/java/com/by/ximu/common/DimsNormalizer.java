package com.by.ximu.common;

/**
 * 库存五维字符串归一化（P1-4）：消除「同物异写」导致的库存行裂变。
 *
 * <p>库存账本以 {@code org_id + product_name + material + spec + grade} 五个字符串作为业务身份，
 * 前导/尾部空格、全角字母数字与半角混用等不可见差异会在 {@code findStock} 五维精确匹配时 miss，
 * 静默裂变为第二行库存行，盘点对账时才暴露。本类在所有五维写入点统一做归一化。
 *
 * <p>归一规则：
 * <ul>
 *   <li>{@code null} 原样返回——"缺省"语义（联动时归一为空串匹配）由调用方处理，不能吞掉；</li>
 *   <li>全角空格 U+3000 → 半角空格；全角 ASCII 区 U+FF01~U+FF5E → 对应半角（{@code ＡＢＣ123} → {@code ABC123}，
 *       全角形式标点如 ，：；！ 也转半角）；中文汉字与 CJK 专用标点（、。《》等，U+3000 段）不受影响；</li>
 *   <li>首尾空白 trim（全角空格已先转半角，trim 后无残留）；</li>
 *   <li>大小写不做归一——MySQL {@code utf8mb4_*_ci} 排序规则下比较本就不区分大小写，
 *       Java 侧再转反而会改动落库显示值。</li>
 * </ul>
 *
 * <p>纯函数、无状态，单测见 {@code DimsNormalizerTest}。
 */
public final class DimsNormalizer {

    private DimsNormalizer() {
    }

    /**
     * 归一化单个维度值：null 原样返回；否则全角转半角后去首尾空白。
     *
     * @param value 维度原始值（品名/物料/规格/等级）
     * @return 归一化后的值；入参为 null 时返回 null
     */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return toHalfWidth(value).trim();
    }

    /** 全角→半角：U+3000 全角空格转普通空格；U+FF01~U+FF5E 全角 ASCII 区偏移 -0xFEE0 */
    static String toHalfWidth(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u3000') {
                sb.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
