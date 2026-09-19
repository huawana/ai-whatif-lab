package com.whatif.lab.ai.agent;

import com.whatif.lab.ai.AiInvoker;
import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.api.dto.ExperimentResultView;
import com.whatif.lab.common.BizException;
import com.whatif.lab.service.ExperimentService;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一键 What-If：自然语言 → DSL → 实验 → 解释（「一句话到结论」的完整闭环）。
 *
 * <p><b>这个类是整条项目主线的可执行形态</b>：
 * <pre>
 *   "如果满100减10改成满80减15，模拟未来30天"
 *        ↓ ScenarioCompiler（LLM 编译，带校验与自我纠正）
 *   Scenario DSL
 *        ↓ ExperimentService（校验 → 落库 → 仿真，同步跑完）
 *   指标对比（Baseline vs Scenario + 显著性）
 *        ↓ ResultExplainer（只组织语言，不算数字）
 *   业务解释
 * </pre>
 *
 * <p><b>降级是分段的，不是全有全无</b>：没配 key 时第 1 步与第 5 步不可用，
 * 但中间的 2~4 步（确定性链路）完全不受影响 —— 用户仍然可以手工提交 DSL 拿到结果。
 * 这是本项目对「AI 是可选增值能力」最实在的证明：
 * <b>AI 挂掉时，平台的核心价值（可复现的量化仿真）一点都没少。</b>
 */
@Component
public class WhatIfAssistant {

    private static final Logger log = LoggerFactory.getLogger(WhatIfAssistant.class);

    private final ScenarioCompiler compiler;
    private final ResultExplainer explainer;
    private final ExperimentService experimentService;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AiProperties properties;
    private final AiInvoker invoker;
    private final ExperimentAgentFactory agentFactory;

    public WhatIfAssistant(ScenarioCompiler compiler,
                           ResultExplainer explainer,
                           ExperimentService experimentService,
                           ObjectProvider<ChatModel> chatModelProvider,
                           AiProperties properties,
                           AiInvoker invoker,
                           ExperimentAgentFactory agentFactory) {
        this.compiler = compiler;
        this.explainer = explainer;
        this.experimentService = experimentService;
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
        this.invoker = invoker;
        this.agentFactory = agentFactory;
    }

    /** 一键流程的结果。 */
    public record OneShot(boolean aiAvailable,
                          String aiError,
                          ScenarioCompiler.Compilation compilation,
                          ExperimentResultView experiment,
                          ResultExplainer.Explanation explanation,
                          long elapsedMs) {
    }

    /**
     * @param datasetId    数据集
     * @param text         自然语言假设
     * @param simulations  仿真次数（null → 默认 200）
     * @param durationDays 模拟周期（null → DSL 里的值或默认 30）
     */
    public OneShot whatIf(Long datasetId, String text, Integer simulations, Integer durationDays) {
        long start = System.currentTimeMillis();
        if (!compiler.available()) {
            throw BizException.aiDisabled(compiler.unavailableReason()
                    + "。请改用确定性入口：POST /api/experiments 并直接提供 dsl。");
        }
        ScenarioCompiler.Compilation compilation = compiler.compile(datasetId, text);
        if (compilation.upstreamFailed()) {
            // 上游没调通就别继续了：重试与改 Prompt 都无意义，明确报错并给出确定性替代路径
            throw BizException.aiFailed(compilation.upstreamError()
                    + "；请检查上游账户状态（欠费/限流）或稍后重试。"
                    + "确定性入口不受影响：POST /api/experiments 直接提供 dsl。");
        }
        if (!compilation.success()) {
            return new OneShot(true, compilation.error(), compilation, null, null,
                    System.currentTimeMillis() - start);
        }
        ScenarioDsl dsl = compilation.dsl();
        ExperimentService.CreateRequest req = new ExperimentService.CreateRequest(
                datasetId, null, dsl, text, simulations,
                durationDays != null ? durationDays : dsl.durationDays(),
                null, null, null, "AI", true);
        ExperimentResultView view = experimentService.create(req);
        ResultExplainer.Explanation explanation = explainer.explain(view);
        log.info("一键 What-If 完成 datasetId={} experimentId={} 编译尝试={} AI解释={} 耗时={}ms",
                datasetId, view.experimentId(), compilation.attempts(), explanation.aiAvailable() ? "有" : "无",
                System.currentTimeMillis() - start);
        return new OneShot(true, null, compilation, view, explanation, System.currentTimeMillis() - start);
    }

    /** Agent 自由问答（工具编排）。 */
    public Map<String, Object> ask(String question, boolean readOnly) {
        var agentOpt = readOnly ? agentFactory.advisor() : agentFactory.analyst();
        Map<String, Object> out = new LinkedHashMap<>();
        if (agentOpt.isEmpty()) {
            out.put("aiAvailable", false);
            out.put("answer", null);
            out.put("error", "AI 不可用：" + agentFactory.unavailableReason()
                    + "。确定性入口不受影响（/api/experiments、/api/datasets）。");
            return out;
        }
        AiInvoker.Result result = invoker.invoke(agentOpt.get(), question);
        out.put("aiAvailable", true);
        out.put("agent", readOnly ? "whatif_advisor" : "whatif_analyst");
        out.put("answer", result.isOk() ? result.text() : null);
        out.put("error", result.error());
        out.put("timeout", result.timeout());
        return out;
    }

    public boolean available() {
        return properties.isUsable() && chatModelProvider.getIfAvailable() != null;
    }

    public String modelName() {
        return properties.effectiveModel();
    }
}
