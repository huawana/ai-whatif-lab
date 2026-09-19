package com.whatif.lab.ai.agent;

import com.whatif.lab.ai.AiInvoker;
import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.api.dto.ExperimentResultView;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.metric.MetricType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结果解释器：把仿真结果翻译成业务语言。
 *
 * <p><b>它的输入被严格限制为「引擎算出来的数字」</b>：
 * 提示词里给出的是 metrics 表（原始值 + 变化率 + 显著性）与诊断信息，
 * 并要求「数字必须来自这份表格，不得引入任何其他数字」。
 * 于是「AI 解释结果」这件事的风险被压到最低：模型只做语言组织，不做数值推断。
 *
 * <p>这也是与「LLM 直接预测业务数据」的本质区别：
 * <pre>
 *   错误做法：用户 → LLM → "订单量会涨 12%"      （数字没有可复现的来源）
 *   本平台：用户 → LLM → DSL → 仿真引擎 → 数字 → LLM 组织语言
 * </pre>
 */
@Component
public class ResultExplainer {

    private static final Logger log = LoggerFactory.getLogger(ResultExplainer.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AiProperties properties;
    private final AiInvoker invoker;
    private final JsonCodec json;

    public ResultExplainer(ObjectProvider<ChatModel> chatModelProvider,
                           AiProperties properties,
                           AiInvoker invoker,
                           JsonCodec json) {
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
        this.invoker = invoker;
        this.json = json;
    }

    public boolean available() {
        return properties.isUsable() && chatModelProvider.getIfAvailable() != null;
    }

    public record Explanation(String text, boolean aiAvailable, String error, Map<String, Object> facts) {
    }

    public Explanation explain(ExperimentResultView view) {
        Map<String, Object> facts = buildFacts(view);
        if (!available()) {
            return new Explanation(null, false, properties.unavailableReason() == null
                    ? "ChatModel 未装配" : properties.unavailableReason(), facts);
        }
        AiInvoker.Result result = invoker.call(chatModelProvider.getIfAvailable(), systemPrompt(), json.write(facts));
        if (!result.isOk()) {
            return new Explanation(null, false, result.error(), facts);
        }
        return new Explanation(result.text(), true, null, facts);
    }

    /** 事实包：解释的唯一数据来源，同时原样返回给调用方（可审计、可复现）。 */
    public Map<String, Object> buildFacts(ExperimentResultView view) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("experimentId", view.experimentId());
        facts.put("status", view.status());
        facts.put("baselineRuleSet", view.baselineRuleSet());
        facts.put("scenarioRuleSet", view.scenarioRuleSet());
        facts.put("simulationCount", view.simulationCount());
        facts.put("randomSeed", view.randomSeed());
        facts.put("model", view.behaviorModelInfo());
        facts.put("durationDays", view.durationDays());

        if (view.comparisons() != null) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var c : view.comparisons()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metric", c.label() + "(" + c.metric().name() + ")");
                row.put("unit", c.unit());
                row.put("baselineMean", round(c.baseline().mean()));
                row.put("scenarioMean", round(c.scenario().mean()));
                row.put("baselineP5", round(c.baseline().p5()));
                row.put("baselineP95", round(c.baseline().p95()));
                row.put("scenarioP5", round(c.scenario().p5()));
                row.put("scenarioP95", round(c.scenario().p95()));
                row.put("changePercent", round(c.changeRate() * 100));
                row.put("pairedDeltaMean", round(c.deltaMean()));
                row.put("tStatistic", c.tStat());
                row.put("significant", c.significant());
                rows.add(row);
            }
            facts.put("metrics", rows);
        }
        facts.put("metricDefinitions", definitions());
        facts.put("diagnostics", view.diagnostics());
        facts.put("cacheHit", view.cacheHit());
        return facts;
    }

    private List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (MetricType t : MetricType.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metric", t.name());
            m.put("definition", t.definition());
            defs.add(m);
        }
        return defs;
    }

    private String systemPrompt() {
        return """
                你是「AI What-If Lab」的结果解释助手。下面会给你一份 JSON 事实包，包含一次仿真实验的
                Baseline 与 Scenario 两臂指标、变化率、配对 t 统计量、显著性、指标口径定义与诊断信息。

                你的任务：用中文写一段面向业务决策者的解释，结构为
                ① 一句话结论（改了哪条规则、关键指标动了多少、是否显著）；
                ② 2~4 条要点（哪个指标变化最大、优惠成本与利润的关系、最差情况 P5 是多少）；
                ③ 风险与口径提醒（采样口径导致绝对量级不可直接比较；若外推比例>0 要提示外推风险）。

                铁律：
                - 只能使用事实包里的数字，一个都不能自己算、自己加、自己四舍五入成另一种说法。
                  需要引用某个数字时，用事实包里的原值。
                - significantly 为 false 的指标，必须表述为"差异在随机波动范围内，不构成有效结论"。
                - 提到利润时，必须说明成本是按毛利率假设算出来的（不是数据里来的）。
                - 控制在 400 字以内，不要输出 JSON、不要输出表格、不要复述全部原始指标。
                """;
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
