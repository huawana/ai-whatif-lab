package com.whatif.lab.domain.rule;

/**
 * 规则类型 —— 第一版实现三种可插拔规则。
 *
 * <p>为什么只有三种（而不是把文档里的四种都写上）：<b>规则必须是仿真引擎真的会执行的代码</b>。
 * 游戏域的资源规则（energy -10）在没有游戏数据集的情况下只能是死代码，
 * 而「写了但从不被执行」的规则类型在面试时是负分项。
 * 这里保留三种在电商域内可被完整验证的规则，并留出 {@code RuleHandler} SPI，
 * 换领域时新增一种类型 = 新增一个 handler，引擎本身零改动。
 */
public enum RuleType {

    /**
     * 价格规则：直接改变结算单价。
     * <pre>{"multiply": 0.9}  或  {"add": -5}</pre>
     * 语义：effectiveUnitPrice = listPrice × multiply + add
     */
    PRICE,

    /**
     * 优惠规则：按订单（购物篮）金额门槛减免。
     * <pre>{"threshold": 100, "amount": 10}  或  {"threshold": 100, "percent": 0.05}</pre>
     * 语义：Σ有效行金额 ≥ threshold 时，减免 amount 元 或 percent 比例。
     * 这个规则是「AI 说的那句话」（满 100 减 10 改成满 80 减 15）的直接落点。
     */
    DISCOUNT,

    /**
     * 行为概率规则：直接缩放购买概率，用于表达「非价格因素」的策略
     * （例如推荐位加权、活动曝光、抽卡概率提升）。
     * <pre>{"multiplier": 1.1}</pre>
     * 语义：p' = clip(p × multiplier, 0, 1)
     */
    PROBABILITY
}
