package com.whatif.lab.ai;

import com.whatif.lab.ai.agent.ExperimentAgentFactory;
import com.whatif.lab.ai.agent.ResultExplainer;
import com.whatif.lab.ai.agent.ScenarioCompiler;
import com.whatif.lab.ai.agent.WhatIfAssistant;
import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.ai.tool.ExperimentTools;
import com.whatif.lab.common.ApiResponse;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.service.ExperimentService;
import com.whatif.lab.service.ScenarioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力 API（Spring AI Alibaba）。
 *
 * <p>四个能力对应文档第二十三节：
 * <ol>
 *   <li>{@code POST /api/ai/scenario/compile} —— Scenario Generator：自然语言 → DSL；</li>
 *   <li>{@code POST /api/ai/whatif} —— 一键闭环：编译 → 仿真 → 解释；</li>
 *   <li>{@code POST /api/ai/validate} —— 校验助手（DSL 校验的语义层问答）；</li>
 *   <li>{@code POST /api/ai/explain/{experimentId}} —— Result Explanation；</li>
 *   <li>{@code POST /api/ai/ask} —— Agent（Tool Calling）自由问答。</li>
 * </ol>
 *
 * <p><b>AI 关闭时的行为是「明确的业务错误」，不是 500，也不是静默降级</b>：
 * 每个端点都返回 {@code code=1001} + 「AI 不可用」的原因，并附上确定性替代路径。
 * 调用方（前端/AI Agent/脚本）能清楚区分「这个功能被关掉了」和「平台坏了」。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiProperties properties;
    private final ScenarioCompiler compiler;
    private final ResultExplainer explainer;
    private final WhatIfAssistant assistant;
    private final ExperimentAgentFactory agentFactory;
    private final ExperimentTools tools;
    private final ExperimentService experimentService;
    private final ScenarioService scenarioService;
    private final AiInvoker invoker;
    private final JsonCodec json;

    public AiController(AiProperties properties,
                        ScenarioCompiler compiler,
                        ResultExplainer explainer,
                        WhatIfAssistant assistant,
                        ExperimentAgentFactory agentFactory,
                        ExperimentTools tools,
                        ExperimentService experimentService,
                        ScenarioService scenarioService,
                        AiInvoker invoker,
                        JsonCodec json) {
        this.properties = properties;
        this.compiler = compiler;
        this.explainer = explainer;
        this.assistant = assistant;
        this.agentFactory = agentFactory;
        this.tools = tools;
        this.experimentService = experimentService;
        this.scenarioService = scenarioService;
        this.invoker = invoker;
        this.json = json;
    }

    /** AI 状态：可用性、模型、工具清单、降级原因。前端据此决定是否显示 AI 入口。 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aiAvailable", assistant.available());
        out.put("enabled", properties.enabled());
        out.put("model", properties.effectiveModel());
        // 暴露供应商与 base-url（只暴露主机名，不含路径与 key）：换供应商后一眼能看出实际在用谁。
        out.put("provider", properties.isDashScope() ? "dashscope" : String.valueOf(properties.provider()));
        out.put("baseUrlHost", hostOf(properties.effectiveBaseUrl()));
        out.put("unavailableReason", properties.unavailableReason());
        out.put("timeoutMillis", properties.timeoutMillis());
        out.put("maxIterations", properties.maxIterations());
        out.put("agentPoolSize", invoker.poolSize());
        out.put("agentQueueSize", invoker.queueSize());
        out.put("agents", List.of("whatif_analyst（可跑实验）", "whatif_advisor（只读）"));
        out.put("tools", tools.toolNames());
        out.put("degradation", "AI 不可用时：Scenario DSL 仍可手工提交（POST /api/scenarios），"
                + "实验、缓存、SSE、结果查询完全不依赖 AI。");
        return ApiResponse.ok(out);
    }

    /** 自然语言 → Scenario DSL（只编译不执行）。 */
    @PostMapping("/scenario/compile")
    public ApiResponse<Map<String, Object>> compile(@RequestBody Map<String, Object> body) {
        Long datasetId = longOf(body.get("datasetId"));
        String text = str(body.get("text"));
        if (datasetId == null || text == null) {
            throw BizException.validation("datasetId 与 text 不能为空");
        }
        ScenarioCompiler.Compilation c = compiler.compile(datasetId, text);
        /*
         * 【为什么这里要抛而不是照常返回】上游失败（欠费/限流/超时）时如果返回
         * code=0 + success=false，调用方会把它读成"编译成功但情景不合规"，
         * 于是去改 Prompt、重试、怀疑模型 —— 而真因是账户欠费（实测踩到 Arrearage）。
         * 所以基础设施故障必须用它自己的错误码 1005 冒出来。
         */
        if (c.upstreamFailed()) {
            throw BizException.aiFailed(c.upstreamError()
                    + "；请检查上游账户状态（欠费/限流）或稍后重试。"
                    + "确定性入口不受影响：POST /api/experiments 直接提供 dsl。");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", c.success());
        out.put("attempts", c.attempts());
        out.put("dsl", c.dsl());
        out.put("validation", c.validation());
        out.put("rawModelOutput", c.rawModelOutput());
        out.put("error", c.error());
        return ApiResponse.ok(out);
    }

    /** 一键 What-If：一句话 → 结论（编译 + 仿真 + 解释）。 */
    @PostMapping("/whatif")
    public ApiResponse<Map<String, Object>> whatif(@RequestBody Map<String, Object> body) {
        Long datasetId = longOf(body.get("datasetId"));
        String text = str(body.get("text"));
        if (datasetId == null || text == null) {
            throw BizException.validation("datasetId 与 text 不能为空");
        }
        WhatIfAssistant.OneShot shot = assistant.whatIf(datasetId, text,
                intOf(body.get("simulations")), intOf(body.get("durationDays")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aiAvailable", shot.aiAvailable());
        out.put("aiError", shot.aiError());
        out.put("compilation", shot.compilation());
        out.put("experiment", shot.experiment());
        out.put("explanation", shot.explanation() == null ? null : shot.explanation());
        out.put("elapsedMs", shot.elapsedMs());
        return ApiResponse.ok(out);
    }

    /** 只取主机名，避免把内网路径、带 token 的 URL 暴露给前端。 */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "-";
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Agent 自由问答（默认用可跑实验的 analyst；readOnly=true 用只读顾问）。 */
    @PostMapping("/ask")
    public ApiResponse<Map<String, Object>> ask(@RequestBody Map<String, Object> body) {
        String question = str(body.get("question"));
        if (question == null) {
            throw BizException.validation("question 不能为空");
        }
        return ApiResponse.ok(assistant.ask(question, Boolean.TRUE.equals(body.get("readOnly"))));
    }

    /** 解释已有实验结果（数字全部来自引擎，模型只组织语言）。 */
    @PostMapping("/explain/{experimentId}")
    public ApiResponse<Map<String, Object>> explain(@PathVariable Long experimentId) {
        var view = experimentService.view(experimentId);
        ResultExplainer.Explanation e = explainer.explain(view);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", experimentId);
        out.put("headline", view.headline());
        out.put("aiAvailable", e.aiAvailable());
        out.put("explanation", e.text());
        out.put("error", e.error());
        out.put("facts", e.facts());
        return ApiResponse.ok(out);
    }

    /** 让模型对一份 DSL 做「人话复述 + 风险提示」（校验助手的对话面）。 */
    @PostMapping("/scenario/describe")
    public ApiResponse<Map<String, Object>> describe(@RequestBody Map<String, Object> body) {
        Long datasetId = longOf(body.get("datasetId"));
        ScenarioDsl dsl = json.read(json.write(body.get("dsl")), ScenarioDsl.class);
        ScenarioService.ValidationResult validation = scenarioService.validate(datasetId, dsl, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baselineRuleSet", validation.baselineRules().describe());
        out.put("scenarioRuleSet", validation.scenarioRules().describe());
        out.put("warnings", validation.warnings());
        out.put("canonicalForm", validation.canonicalForm());
        out.put("dslHash", validation.dslHash());
        return ApiResponse.ok(out);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Long longOf(Object v) {
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }

    private static Integer intOf(Object v) {
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }
}
