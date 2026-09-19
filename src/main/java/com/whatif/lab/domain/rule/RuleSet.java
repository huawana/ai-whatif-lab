package com.whatif.lab.domain.rule;

import java.util.List;

/**
 * 规则集：同一版本的多条规则构成一套「当前业务规则」。
 *
 * <p>为什么要按「集」而不是按「条」做版本：规则之间有交互（价格×优惠），
 * 单条规则单独版本化会让「Baseline 到底是哪几条规则的组合」变得无法回答。
 * 一套规则集一个版本号，实验只引用版本号，历史实验就能完整复现。
 *
 * <p>看 {@link #describe()} —— 规则集的可读描述是实验报告与 AI 解释的输入，
 * 让「这次实验到底改了哪条规则」在结果里可追溯，而不是靠人回忆。
 */
public record RuleSet(int version, String name, List<RuleSpec> rules) {

    public RuleSet {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public List<RuleSpec> ofType(RuleType type) {
        return rules.stream().filter(r -> r.type() == type).toList();
    }

    /** 人类可读的规则集描述（写进实验快照，供结果对比与 AI 解释引用）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(name == null ? ("规则集 v" + version) : name).append("(v").append(version).append("): ");
        if (rules.isEmpty()) {
            sb.append("无规则（自由市场基线）");
        }
        for (int i = 0; i < rules.size(); i++) {
            RuleSpec r = rules.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(switch (r.type()) {
                case PRICE -> "价格×" + r.getDouble("multiply", 1.0)
                        + (r.has("add") ? ("+" + r.getDouble("add", 0)) : "");
                case DISCOUNT -> "满" + trim(r.getDouble("threshold", 0)) + "减"
                        + (r.has("percent") ? (trim(r.getDouble("percent", 0) * 100) + "%")
                        : trim(r.getDouble("amount", 0)));
                case PROBABILITY -> "购买概率×" + r.getDouble("multiplier", 1.0);
            });
        }
        return sb.toString();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
