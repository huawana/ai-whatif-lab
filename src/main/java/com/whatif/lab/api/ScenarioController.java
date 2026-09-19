package com.whatif.lab.api;

import com.whatif.lab.common.ApiResponse;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.persistence.po.ScenarioPO;
import com.whatif.lab.service.ScenarioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情景（Scenario DSL）API。
 *
 * <p><b>这一组端点是「没有 AI 也能用」的证据</b>：DSL 手工 POST 进来，
 * 校验、落库、仿真、对比全部照常工作。AI 只是在同一个入口前面加了一个
 * 「自然语言 → DSL」的编译器。面试时可以现场演示：
 * 关掉 {@code whatif.ai.enabled}，整条链路依然完整可用。
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final JsonCodec json;

    public ScenarioController(ScenarioService scenarioService, JsonCodec json) {
        this.scenarioService = scenarioService;
        this.json = json;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long datasetId,
                                                      @RequestParam(defaultValue = "50") int limit) {
        List<ScenarioPO> rows = scenarioService.list(datasetId, limit);
        return ApiResponse.ok(rows.stream()
                .map(po -> scenarioService.describe(po, scenarioService.dslOf(po)))
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        ScenarioPO po = scenarioService.get(id);
        return ApiResponse.ok(scenarioService.describe(po, scenarioService.dslOf(po)));
    }

    /** 只校验、不落库（AI 的工具 validate_scenario 走这个）。 */
    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        Long datasetId = longOf(body.get("datasetId"));
        ScenarioDsl dsl = parseDsl(body);
        ScenarioService.ValidationResult result = scenarioService.validate(datasetId, dsl, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", result.valid());
        out.put("message", result.message());
        out.put("warnings", result.warnings());
        out.put("dslHash", result.dslHash());
        out.put("canonicalForm", result.canonicalForm());
        out.put("baselineRuleSet", result.baselineRules().describe());
        out.put("scenarioRuleSet", result.scenarioRules().describe());
        out.put("ruleDiff", diff(result.baselineRules().rules(), result.scenarioRules().rules()));
        return ApiResponse.ok(out);
    }

    /** 创建情景（落库，不跑实验）。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Long datasetId = longOf(body.get("datasetId"));
        if (datasetId == null) {
            throw BizException.validation("datasetId 不能为空");
        }
        ScenarioDsl dsl = parseDsl(body);
        ScenarioService.ValidationResult validation = scenarioService.validate(datasetId, dsl, null);
        ScenarioPO po = scenarioService.save(datasetId, dsl,
                body.get("naturalLanguage") == null ? null : String.valueOf(body.get("naturalLanguage")),
                body.get("source") == null ? "MANUAL" : String.valueOf(body.get("source")), validation);
        Map<String, Object> out = scenarioService.describe(po, dsl);
        out.put("warnings", validation.warnings());
        out.put("scenarioRuleSet", validation.scenarioRules().describe());
        return ApiResponse.ok(out);
    }

    private List<String> diff(List<com.whatif.lab.domain.rule.RuleSpec> baseline,
                              List<com.whatif.lab.domain.rule.RuleSpec> scenario) {
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < scenario.size(); i++) {
            String before = i < baseline.size() ? baseline.get(i).params().toString() : "(无)";
            String after = scenario.get(i).params().toString();
            if (!before.equals(after)) {
                lines.add(scenario.get(i).type() + ": " + before + " → " + after);
            }
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private ScenarioDsl parseDsl(Map<String, Object> body) {
        Object raw = body.get("dsl") == null ? body : body.get("dsl");
        if (raw instanceof String s) {
            ScenarioDsl dsl = json.read(s, ScenarioDsl.class);
            if (dsl == null) {
                throw BizException.validation("dsl 解析结果为空");
            }
            return dsl;
        }
        // 用 JsonCodec 统一做 Map → record 的转换，避免两套映射规则
        return json.read(json.write(raw), ScenarioDsl.class);
    }

    private static Long longOf(Object v) {
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }
}
