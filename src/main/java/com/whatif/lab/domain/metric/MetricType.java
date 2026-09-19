package com.whatif.lab.domain.metric;

/**
 * 指标定义 —— 口径写死在枚举里，是全项目唯一的「口径真相」。
 *
 * <p>为什么口径必须显式定义：RAG 里可以上传「GMV 怎么算」的文档，
 * 但如果代码里的算法与文档不一致，AI 解释出来的每个数字都会是错的。
 * 这里的 {@link #definition()} 就是那份文档的代码版本，两者是同源的。
 */
public enum MetricType {

    /** 成交总额（折扣后实收金额，Σ 订单净额）。 */
    GMV("成交总额", "元", "Σ 每个订单折扣后的实付金额（netAmount）。不含取消订单。"),

    /** 订单量：一次「有成交的购物会话」算一单。 */
    ORDERS("订单量", "单", "订单数 = 有成交的购物会话数（数量≥1即算一单）。"),

    /** 转化率：有成交的会话 / 全部会话。 */
    CONVERSION("转化率", "%", "转化率 = 有成交会话数 ÷ 全部购物会话数。"),

    /** 优惠成本：本实验为折扣付出的钱（Baseline 为 0 时表示无优惠规则）。 */
    DISCOUNT_COST("优惠成本", "元", "Σ 所有订单被减免掉的金额。" ),

    /**
     * 利润 = 净收入 − 成本。
     * <b>成本口径是假设（毛利率参数），不是从数据里学出来的</b> —— UCI 数据没有成本列，
     * 所以它必须可配置、可做敏感性分析，并且在报告里显式声明。
     */
    PROFIT("利润", "元", "利润 = 成交总额 − 成本；成本 = 原价 × (1 − 毛利率)，毛利率为配置参数（默认 35%）。"),

    /** 客单价。 */
    AOV("客单价", "元", "客单价 = 成交总额 ÷ 订单量。"),

    /** 毛收入（未减优惠），用于把「优惠前规模」与「优惠成本」分开看。 */
    GROSS_REVENUE("毛收入", "元", "Σ 每个订单折扣前的金额（grossAmount）。");

    private final String label;
    private final String unit;
    private final String definition;

    MetricType(String label, String unit, String definition) {
        this.label = label;
        this.unit = unit;
        this.definition = definition;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }

    public String definition() {
        return definition;
    }
}
