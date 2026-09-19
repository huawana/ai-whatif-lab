package com.whatif.lab.api;

import com.whatif.lab.api.dto.ExperimentResultView;
import com.whatif.lab.cache.ResultCache;
import com.whatif.lab.common.ApiResponse;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.metric.MetricStats;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.service.ExperimentService;
import com.whatif.lab.service.ScenarioService;
import com.whatif.lab.task.ExperimentWorker;
import com.whatif.lab.task.ProgressPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验 API：创建 / 查询 / 对比 / 进度 / 缓存统计。
 *
 * <p>「创建实验」支持两种模式，见 {@code sync} 参数：
 * <ul>
 *   <li>{@code sync=false}（默认，异步）：立即返回 PENDING + experimentId，
 *       进度用 {@code GET /api/experiments/{id}/progress} 的 SSE 订阅；</li>
 *   <li>{@code sync=true}：当前线程算完再返回完整结果，给脚本/验证/小实验用。</li>
 * </ul>
 * 两条路径共用同一个 {@link ExperimentWorker}，所以结果完全一致 —— 这一点
 * 在验证脚本里会被显式断言（同参数同步跑两次结果必须逐位相同，且第二次命中缓存）。
 */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;
    private final ExperimentWorker worker;
    private final ScenarioService scenarioService;
    private final ResultCache resultCache;
    private final ProgressPublisher progressPublisher;
    private final JsonCodec json;

    public ExperimentController(ExperimentService experimentService,
                                ExperimentWorker worker,
                                ScenarioService scenarioService,
                                ResultCache resultCache,
                                ProgressPublisher progressPublisher,
                                JsonCodec json) {
        this.experimentService = experimentService;
        this.worker = worker;
        this.scenarioService = scenarioService;
        this.resultCache = resultCache;
        this.progressPublisher = progressPublisher;
        this.json = json;
    }

    @PostMapping
    public ApiResponse<ExperimentResultView> create(@RequestBody Map<String, Object> body) {
        Long datasetId = longOf(body.get("datasetId"));
        Long scenarioId = longOf(body.get("scenarioId"));
        ScenarioDsl dsl = body.get("dsl") == null ? null
                : json.read(json.write(body.get("dsl")), ScenarioDsl.class);

        ExperimentService.CreateRequest req = new ExperimentService.CreateRequest(
                datasetId, scenarioId, dsl,
                str(body.get("naturalLanguage")),
                intOf(body.get("simulations")),
                intOf(body.get("durationDays")),
                longOf(body.get("seed")),
                intOf(body.get("activeCustomers")),
                intOf(body.get("candidatesPerCustomer")),
                str(body.getOrDefault("source", "MANUAL")),
                Boolean.TRUE.equals(body.get("sync")));
        return ApiResponse.ok(experimentService.create(req));
    }

    /**
     * 恒等实验快捷入口：直接跑「不改任何规则」的情景。
     *
     * <p>它的存在意义是<b>自检</b>：恒等实验的两臂必须逐位相同
     * （Baseline 与 Scenario 用同一套随机流），GMV 变化率必须精确等于 0。
     * 这是整个仿真引擎最强的一条正确性断言 —— 如果随机流配对写错了，
     * 这里会立刻暴露成「变化率 0.3% 但方向随机」。
     */
    @PostMapping("/identity")
    public ApiResponse<ExperimentResultView> identity(@RequestBody Map<String, Object> body) {
        Long datasetId = longOf(body.get("datasetId"));
        if (datasetId == null) {
            throw BizException.validation("datasetId 不能为空");
        }
        ScenarioDsl dsl = new ScenarioDsl(datasetId, "恒等实验(不改规则)", intOf(body.get("durationDays")), List.of(), Map.of());
        ExperimentService.CreateRequest req = new ExperimentService.CreateRequest(
                datasetId, null, dsl, "恒等性自检：不改变任何规则",
                intOf(body.get("simulations")), intOf(body.get("durationDays")), longOf(body.get("seed")),
                intOf(body.get("activeCustomers")), intOf(body.get("candidatesPerCustomer")),
                "SELFTEST", true);
        return ApiResponse.ok(experimentService.create(req));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) Long datasetId,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(experimentService.list(datasetId, status, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<ExperimentResultView> get(@PathVariable Long id) {
        return ApiResponse.ok(experimentService.view(id));
    }

    /** 结果表：把两臂指标摊平成「指标 / 基线 / 情景 / 变化率 / 显著性」的表格。 */
    @GetMapping("/{id}/table")
    public ApiResponse<Map<String, Object>> table(@PathVariable Long id) {
        ExperimentResultView v = experimentService.view(id);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        if (v.comparisons() != null) {
            for (var c : v.comparisons()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metric", c.metric().name());
                row.put("label", c.label());
                row.put("unit", c.unit());
                row.put("baselineMean", round(c.baseline().mean()));
                row.put("scenarioMean", round(c.scenario().mean()));
                row.put("baselineP5", round(c.baseline().p5()));
                row.put("baselineP95", round(c.baseline().p95()));
                row.put("scenarioP5", round(c.scenario().p5()));
                row.put("scenarioP95", round(c.scenario().p95()));
                row.put("changeRate", round(c.changeRate() * 100));
                row.put("deltaMean", round(c.deltaMean()));
                row.put("tStat", c.tStat());
                row.put("pValue", c.pValue());
                row.put("ci95Low", round(c.ci95Low()));
                row.put("ci95High", round(c.ci95High()));
                row.put("ci95Text", c.confidenceIntervalText());
                row.put("relativeEffect", round(c.relativeEffect() * 100));
                row.put("cohensD", c.cohensD());
                row.put("effectSizeLevel", c.effectSizeLevel());
                row.put("significant", c.significant());
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("experimentId", id);
        out.put("status", v.status());
        out.put("headline", v.headline());
        out.put("baselineRuleSet", v.baselineRuleSet());
        out.put("scenarioRuleSet", v.scenarioRuleSet());
        out.put("rows", rows);
        out.put("diagnostics", v.diagnostics());
        out.put("cacheHit", v.cacheHit());
        return ApiResponse.ok(out);
    }

    @GetMapping("/{id}/progress")
    public SseEmitter progress(@PathVariable Long id) {
        worker.requireExperiment(id);   // 不存在直接 404，不要开一个永远不会推数据的 SSE
        return progressPublisher.subscribe(id);
    }

    @GetMapping("/{id}/progress/snapshot")
    public ApiResponse<Map<String, Object>> progressSnapshot(@PathVariable Long id) {
        Map<String, Object> snap = progressPublisher.snapshotOf(id);
        if (snap.isEmpty()) {
            ExperimentResultView v = experimentService.view(id);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("experimentId", id);
            fallback.put("status", v.status());
            fallback.put("progress", v.progress());
            fallback.put("stage", v.stage());
            return ApiResponse.ok(fallback);
        }
        return ApiResponse.ok(snap);
    }

    /** 多实验对比（AI 工具 compare_experiments 也走这里）。 */
    @GetMapping("/compare")
    public ApiResponse<List<Map<String, Object>>> compare(@RequestParam String ids) {
        List<Long> parsed = new java.util.ArrayList<>();
        for (String part : ids.split(",")) {
            if (!part.isBlank()) {
                parsed.add(Long.valueOf(part.trim()));
            }
        }
        return ApiResponse.ok(experimentService.compare(parsed));
    }

    /** 缓存与调度统计（压测/缓存对照实验用）。 */
    @GetMapping("/cache-stats")
    public ApiResponse<Map<String, Object>> cacheStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        ResultCache.Stats stats = resultCache.stats();
        out.put("hits", stats.hits());
        out.put("misses", stats.misses());
        out.put("errors", stats.errors());
        out.put("hitRate", round(stats.hitRate() * 100));
        out.put("cacheEnabled", stats.cacheEnabled());
        out.put("redisHealthy", stats.redisHealthy());
        return ApiResponse.ok(out);
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
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
