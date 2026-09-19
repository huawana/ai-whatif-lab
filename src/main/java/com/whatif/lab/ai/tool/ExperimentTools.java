package com.whatif.lab.ai.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.whatif.lab.api.dto.ExperimentResultView;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.metric.MetricType;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.persistence.po.ScenarioPO;
import com.whatif.lab.service.DatasetService;
import com.whatif.lab.service.ExperimentService;
import com.whatif.lab.service.RuleService;
import com.whatif.lab.service.ScenarioService;
import com.whatif.lab.task.ExperimentWorker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 的工具箱 —— 把平台所有能力包装成模型可调用的工具。
 *
 * <p><b>本类最重要的一条设计原则</b>：
 * <b>模型算数会错、判分类会飘，所以一切会被追责的结论都从工具拿。</b>
 * 这里递给模型的不是「数据」，而是「已经过验证的确定性能力」：
 * 校验、创建情景、跑实验、取结果、比实验。模型的价值落在它真正的强项上
 * （理解意图、编排步骤、把数字讲成人话），而所有数字都来自仿真引擎与数据库。
 *
 * <p><b>工具描述（description）就是给模型看的 API 文档</b>：必须写清
 * 「什么时候用它」「参数是什么意思」「返回什么」。写「查询数据集」这种模糊描述的后果是
 * 模型该调的时候不调、不该调的时候乱调，而这从日志上很难看出是描述的问题。
 *
 * <p><b>返回值一律是紧凑 JSON</b>而不是完整对象：工具返回值会全部进模型的上下文，
 * 一个完整的结果视图（含 200 次仿真的样本）能把 token 预算烧光。
 * 所以 {@code run_experiment} 只回均值/变化率/显著性这样的结论性数字。
 */
@Component
public class ExperimentTools {

    private final DatasetService datasetService;
    private final ScenarioService scenarioService;
    private final ExperimentService experimentService;
    private final ExperimentWorker worker;
    private final RuleService ruleService;
    private final JsonCodec json;

    public ExperimentTools(DatasetService datasetService,
                           ScenarioService scenarioService,
                           ExperimentService experimentService,
                           ExperimentWorker worker,
                           RuleService ruleService,
                           JsonCodec json) {
        this.datasetService = datasetService;
        this.scenarioService = scenarioService;
        this.experimentService = experimentService;
        this.worker = worker;
        this.ruleService = ruleService;
        this.json = json;
    }

    // ------------------------------------------------------------------ 入参类型
    // 用 record 定义入参，框架会自动生成 JSON Schema —— 模型据此知道该传什么

    public record DatasetInput(Long datasetId) {
    }

    public record ValidateInput(Long datasetId, String changesJson) {
    }

    public record CreateScenarioInput(Long datasetId, String name, Integer durationDays,
                                      String changesJson, String naturalLanguage) {
    }

    public record RunExperimentInput(Long datasetId, Long scenarioId, Integer simulations,
                                     Integer durationDays, Long seed) {
    }

    public record ExperimentIdInput(Long experimentId) {
    }

    public record CompareInput(String experimentIds) {
    }

    public record ListExperimentsInput(Long datasetId, Integer limit) {
    }

    public record EmptyInput(String note) {
    }

    // ------------------------------------------------------------------ 工具集

    public List<ToolCallback> tools() {
        List<ToolCallback> list = new ArrayList<>();

        list.add(FunctionToolCallback
                .builder("list_datasets", (EmptyInput in) -> json.write(datasetService.list()))
                .description("列出平台上所有数据集（含 datasetId、名称、状态、事件数、当前基线规则版本）。"
                        + "用户没指明用哪个数据集时，先调用它确认。")
                .inputType(EmptyInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("get_dataset_profile", (DatasetInput in) ->
                        json.write(datasetService.profile(in.datasetId())))
                .description("获取指定数据集的画像：当前规则集、每种规则可改的字段、时间范围、"
                        + "事件类型分布、当前行为模型的评估指标（AUC 等）。"
                        + "在做任何情景设计之前必须先调用它，不要凭想象假设规则内容。")
                .inputType(DatasetInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("validate_scenario", (ValidateInput in) -> {
                    ScenarioDsl dsl = dslOf(in.datasetId(), in.changesJson());
                    ScenarioService.ValidationResult r = scenarioService.validate(in.datasetId(), dsl, null);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("valid", r.valid());
                    out.put("message", r.message());
                    out.put("warnings", r.warnings());
                    out.put("baselineRuleSet", r.baselineRules().describe());
                    out.put("scenarioRuleSet", r.scenarioRules().describe());
                    return json.write(out);
                })
                .description("校验一组规则改动是否合法（不落库）。changesJson 形如 "
                        + "[{\"ruleType\":\"DISCOUNT\",\"field\":\"threshold\",\"from\":100,\"to\":80}]。"
                        + "在创建情景或跑实验之前，先调用它确认真实生效的规则集文本。")
                .inputType(ValidateInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("create_scenario", (CreateScenarioInput in) -> {
                    ScenarioDsl dsl = dslOf(in.datasetId(), in.changesJson());
                    dsl = new ScenarioDsl(in.datasetId(), in.name(), in.durationDays(), dsl.changes(), Map.of());
                    ScenarioService.ValidationResult v = scenarioService.validate(in.datasetId(), dsl, null);
                    ScenarioPO saved = scenarioService.save(in.datasetId(), dsl, in.naturalLanguage(),
                            "AI", v);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("scenarioId", saved.getId());
                    out.put("name", saved.getName());
                    out.put("durationDays", saved.getDurationDays());
                    out.put("scenarioRuleSet", v.scenarioRules().describe());
                    out.put("warnings", v.warnings());
                    return json.write(out);
                })
                .description("把规则改动保存成一个情景（Scenario），返回 scenarioId 供 run_experiment 使用。"
                        + "参数 changesJson 与 validate_scenario 相同。保存前会做三层校验，非法会返回错误原因。")
                .inputType(CreateScenarioInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("run_experiment", (RunExperimentInput in) -> json.write(runExperiment(in)))
                .description("对指定情景跑一次蒙特卡洛仿真实验，返回 Baseline 与 Scenario 的指标对比"
                        + "（均值、变化率、是否显著）。这是唯一能产出业务数字的工具，"
                        + "所有结论性数字必须来自它的返回值，严禁自己估算或外推。"
                        + "simulations 默认 200（越多越稳、越慢），durationDays 默认 30。")
                .inputType(RunExperimentInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("get_experiment_result", (ExperimentIdInput in) -> json.write(compact(worker.view(in.experimentId()))))
                .description("按 experimentId 取回已有实验的结果摘要（状态、指标均值、变化率、显著性、诊断信息）。"
                        + "用户提到之前跑过的实验时用它，不要重新跑一遍。")
                .inputType(ExperimentIdInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("compare_experiments", (CompareInput in) -> {
                    List<Long> ids = new ArrayList<>();
                    for (String part : in.experimentIds().split(",")) {
                        if (!part.isBlank()) {
                            ids.add(Long.valueOf(part.trim()));
                        }
                    }
                    return json.write(worker.compare(ids));
                })
                .description("横向对比多个实验的情景指标（传入逗号分隔的 experimentId）。"
                        + "用户问「A 方案和 B 方案哪个好」时使用。")
                .inputType(CompareInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("list_experiments", (ListExperimentsInput in) ->
                        json.write(experimentService.list(in.datasetId(),
                                null, in.limit() == null ? 10 : in.limit())))
                .description("列出最近的实验（可指定 datasetId），含状态、仿真次数、是否命中缓存。")
                .inputType(ListExperimentsInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("get_metric_definitions", (EmptyInput in) -> {
                    List<Map<String, Object>> defs = new ArrayList<>();
                    for (MetricType t : MetricType.values()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("metric", t.name());
                        m.put("label", t.label());
                        m.put("unit", t.unit());
                        m.put("definition", t.definition());
                        defs.add(m);
                    }
                    return json.write(defs);
                })
                .description("获取平台所有指标的准确定义（GMV/订单量/转化率/优惠成本/利润/客单价的口径），"
                        + "以及毛利率成本假设。解释结果或回答「这个指标怎么算」时必须用它，"
                        + "不要用你自己对 GMV 的定义去解释平台的数字。")
                .inputType(EmptyInput.class)
                .build());

        list.add(FunctionToolCallback
                .builder("get_current_rules", (DatasetInput in) -> {
                    RuleSet baseline = ruleService.load(in.datasetId(),
                            ruleService.currentVersion(in.datasetId(), null));
                    return json.write(Map.of(
                            "ruleVersion", baseline.version(),
                            "description", baseline.describe(),
                            "rules", baseline.rules()));
                })
                .description("获取数据集当前生效的基线规则集（Baseline 的准确定义）。"
                        + "用户问「现在是什么规则」或要基于现状做改动时使用。")
                .inputType(DatasetInput.class)
                .build());

        return list;
    }

    public List<String> toolNames() {
        return tools().stream().map(t -> t.getToolDefinition().name()).sorted().toList();
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> runExperiment(RunExperimentInput in) {
        ExperimentService.CreateRequest req = new ExperimentService.CreateRequest(
                in.datasetId(), in.scenarioId(), null, null,
                in.simulations(), in.durationDays(), in.seed(), null, null, "AI", true);
        ExperimentResultView view = experimentService.create(req);
        return compact(view);
    }

    /** 紧凑结果：只保留模型决策需要的结论性数字。 */
    Map<String, Object> compact(ExperimentResultView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", view.experimentId());
        out.put("status", view.status());
        out.put("scenario", view.scenarioName());
        out.put("baselineRuleSet", view.baselineRuleSet());
        out.put("scenarioRuleSet", view.scenarioRuleSet());
        out.put("simulationCount", view.simulationCount());
        out.put("randomSeed", view.randomSeed());
        out.put("headline", view.headline());
        List<Map<String, Object>> rows = new ArrayList<>();
        if (view.comparisons() != null) {
            for (var c : view.comparisons()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metric", c.metric().name());
                row.put("label", c.label());
                row.put("unit", c.unit());
                row.put("baseline", round(c.baseline().mean()));
                row.put("scenario", round(c.scenario().mean()));
                row.put("changePercent", round(c.changeRate() * 100));
                row.put("significant", c.significant());
                row.put("p5", round(c.scenario().p5()));
                row.put("p95", round(c.scenario().p95()));
                rows.add(row);
            }
        }
        out.put("metrics", rows);
        if (view.diagnostics() != null) {
            Map<String, Object> diag = new LinkedHashMap<>();
            for (String k : List.of("activeCustomers", "products", "sessionsPerCustomer",
                    "outOfSupportRatioScenario", "gmvScaleFactor", "fastVsNaiveMaxDiff",
                    "perceivedDiscountBaselineMean", "perceivedDiscountScenarioMean")) {
                if (view.diagnostics().containsKey(k)) {
                    diag.put(k, view.diagnostics().get(k));
                }
            }
            out.put("diagnostics", diag);
            out.put("diagnosticsNote", "gmvScaleFactor = 真实历史同窗口 GMV ÷ 基线仿真 GMV；"
                    + "仿真按「每客户每会话抽样候选商品」的口径计算，绝对值不可与真实业务量级直接比较，"
                    + "请以两臂相对变化作为结论。");
        }
        out.put("cacheHit", view.cacheHit());
        return out;
    }

    private ScenarioDsl dslOf(Long datasetId, String changesJson) {
        List<ScenarioDsl.Change> changes = changesJson == null || changesJson.isBlank()
                ? List.of()
                : json.read(changesJson, new TypeReference<List<ScenarioDsl.Change>>() {
        });
        return new ScenarioDsl(datasetId, "AI 生成情景", null, changes == null ? List.of() : changes, Map.of());
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
