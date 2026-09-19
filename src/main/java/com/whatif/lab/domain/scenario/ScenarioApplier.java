package com.whatif.lab.domain.scenario;

import com.whatif.lab.common.BizException;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.domain.rule.RuleSpec;
import com.whatif.lab.domain.rule.RuleType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scenario 应用器：Baseline 规则集 + DSL 改动 → Scenario 规则集。
 *
 * <p><b>纯函数、无 IO、无状态</b> —— 这样它才能被穷举单测。
 * 这一步是 LLM 与仿真引擎之间的最后一道闸门：
 * 模型输出的每个字段名、每个取值都在这里对着规则定义检查一遍，
 * 不合法就抛出可读的业务错误（而不是让引擎拿着垃圾参数算出漂亮的结果）。
 *
 * <p>为什么用「差分」而不是「完整规则集」（见 {@link ScenarioDsl} 的说明）：
 * 差分天然继承了所有未被提及的规则，LLM 漏输出一条规则不会悄悄改变实验条件。
 * 这是把「模型不完美」这个事实隔离在系统边界之外的工程手段。
 */
public final class ScenarioApplier {

    /** 每种规则的合法字段（与 {@link RuleType} 的语义一一对应）。 */
    private static final Map<RuleType, Set<String>> ALLOWED_FIELDS = Map.of(
            RuleType.PRICE, Set.of("multiply", "add"),
            RuleType.DISCOUNT, Set.of("threshold", "amount", "percent"),
            RuleType.PROBABILITY, Set.of("multiplier"));

    private ScenarioApplier() {
    }

    /**
     * 应用改动。
     *
     * @param baseline 基线规则集（不会被修改）
     * @param changes  DSL 里的改动列表
     * @return 新的规则集（version 不变，name 标注为 Scenario）
     */
    public static RuleSet apply(RuleSet baseline, List<ScenarioDsl.Change> changes) {
        List<RuleSpec> rules = new ArrayList<>(baseline.rules());

        for (ScenarioDsl.Change change : changes) {
            RuleType type = parseType(change.ruleType());
            String field = change.field().trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_FIELDS.get(type).contains(field)) {
                throw BizException.validation("规则 " + type + " 没有字段「" + change.field()
                        + "」，合法字段为 " + ALLOWED_FIELDS.get(type));
            }

            int target = -1;
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).type() == type) {
                    target = i;
                    break;   // 第一版：同类型只作用在第一条规则上（多规则叠加留待扩展）
                }
            }

            double newValue = toDouble(change.to(), type, field);
            Map<String, Object> params = target >= 0
                    ? new LinkedHashMap<>(rules.get(target).params())
                    : new LinkedHashMap<>();
            params.put(field, newValue);
            String description = (target >= 0 ? rules.get(target).description() : null);
            String note = change.note() == null ? "" : ("（" + change.note() + "）");

            RuleSpec updated = new RuleSpec(type, params,
                    (description == null ? type + " 规则" : description) + " → 情景改为 "
                            + field + "=" + newValue + note);
            if (target >= 0) {
                rules.set(target, updated);
            } else {
                rules.add(updated);
            }
        }
        return new RuleSet(baseline.version(), "情景规则(" + baseline.name() + " 的改动)", rules);
    }

    /** DSL 描述是否为空（= 与基线完全一致）。恒等性测试就是靠这种情景构造的。 */
    public static boolean isIdentity(List<ScenarioDsl.Change> changes) {
        return changes == null || changes.isEmpty();
    }

    private static RuleType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.validation("change.ruleType 不能为空");
        }
        try {
            return RuleType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.validation("未知规则类型「" + raw + "」，支持: PRICE / DISCOUNT / PROBABILITY");
        }
    }

    private static double toDouble(Object value, RuleType type, String field) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw BizException.validation("规则 " + type + " 的字段 " + field + " 必须是数字，收到: " + value);
        }
    }
}
