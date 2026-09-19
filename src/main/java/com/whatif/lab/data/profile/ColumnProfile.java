package com.whatif.lab.data.profile;

import java.util.List;

/**
 * 单列的客观画像 —— <b>只描述统计事实，不下语义结论</b>。
 *
 * <p><b>为什么必须把"统计事实"与"语义判断"分开</b>：
 * 「这一列是金额」是语义判断（可能错，需要证据与人确认）；「这一列是小数、非负、P95=6735、两位小数」
 * 是统计事实（可重算、可对账）。平台的做法是：<b>统计事实由确定性代码算（本类），语义判断由 LLM 提议</b>——
 * LLM 只能引用这里的事实作为证据，不能凭空说"我觉得这是金额"。
 * 这样出了错能一眼分清是"统计算错"还是"AI 判错"。
 *
 * @param inferredType INT / DECIMAL / DATETIME / DATE / BOOLEAN / ID / CATEGORY / TEXT / EMPTY
 *                     —— 注意这是**结构类型**，不是语义角色（金额/单价/数量属于语义，不在这里判）
 */
public record ColumnProfile(
        int index,
        String name,
        String inferredType,
        long nonBlankCount,
        long nullCount,
        double nullRate,
        long distinctCount,
        boolean distinctCapped,
        double uniqueRatio,
        Double min,
        Double max,
        Double p05,
        Double p50,
        Double p95,
        long negativeCount,
        long zeroCount,
        long nonIntegralCount,
        Integer minLength,
        Integer maxLength,
        long leadingZeroCount,
        String datePattern,
        Double dateMatchRate,
        List<String> topValues,
        List<String> samples,
        List<String> flags) {

    /** 常用标记（闭集合，便于断言与界面展示）。 */
    public static final String UNIQUE = "UNIQUE";
    public static final String HIGH_CARDINALITY = "HIGH_CARDINALITY";
    public static final String LOW_CARDINALITY = "LOW_CARDINALITY";
    public static final String CONSTANT = "CONSTANT";
    public static final String ALL_NULL = "ALL_NULL";
    public static final String HAS_NULL = "HAS_NULL";
    public static final String HAS_NEGATIVE = "HAS_NEGATIVE";
    public static final String MONEY_LIKE = "MONEY_LIKE";
    public static final String LEADING_ZEROS = "LEADING_ZEROS";
    public static final String DATE_NO_SEPARATOR = "DATE_NO_SEPARATOR";
    public static final String PII_EMAIL = "PII_EMAIL";
    public static final String PII_PHONE = "PII_PHONE";
    public static final String MIXED_TYPE = "MIXED_TYPE";
    /** 取值只有 0 与 1 —— 是"布尔"还是"编码"属于语义判断，统计层只报事实。 */
    public static final String BINARY_VALUES = "BINARY_VALUES";
    /** 几乎每行都不同的数值列：更像标识（ID）而不是度量（金额/数量）。 */
    public static final String LIKELY_IDENTIFIER = "LIKELY_IDENTIFIER";
}
