package com.whatif.lab.domain.rule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条规则的描述（类型 + 参数）。
 *
 * <p>参数用 {@code Map<String,Object>} 而不是每类规则一个强类型子类：
 * 因为规则参数要能被 LLM 生成、被 JSON 原样落库、还要能跨领域扩展。
 * 强类型子类会让「模型输出一个没见过的参数名」直接变成反序列化异常，
 * 而这里可以把它降级成「未知参数，忽略并记录」，对 LLM 输出的容错性高得多。
 * 代价是取值要防御性判断 —— 所以这里提供了带默认值的取值方法，全项目统一走它。
 */
public record RuleSpec(RuleType type, Map<String, Object> params, String description) {

    public RuleSpec {
        params = params == null ? Map.of() : new LinkedHashMap<>(params);
    }

    public double getDouble(String key, double defaultValue) {
        Object v = params.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean has(String key) {
        return params.get(key) != null;
    }

    public String getString(String key, String defaultValue) {
        Object v = params.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }
}
