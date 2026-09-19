package com.whatif.lab.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.whatif.lab.api.dto.ExperimentResultView;
import com.whatif.lab.cache.CacheKeys;
import com.whatif.lab.cache.SingleFlight;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.metric.MetricStats;
import com.whatif.lab.domain.metric.MetricType;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.domain.scenario.ScenarioApplier;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.persistence.mapper.DatasetMapper;
import com.whatif.lab.persistence.mapper.ExperimentMapper;
import com.whatif.lab.persistence.mapper.ExperimentResultMapper;
import com.whatif.lab.persistence.mapper.ExperimentTaskMapper;
import com.whatif.lab.persistence.mapper.MetricResultMapper;
import com.whatif.lab.persistence.mapper.ScenarioMapper;
import com.whatif.lab.persistence.po.DatasetPO;
import com.whatif.lab.persistence.po.ExperimentPO;
import com.whatif.lab.persistence.po.ExperimentResultPO;
import com.whatif.lab.persistence.po.ExperimentTaskPO;
import com.whatif.lab.persistence.po.MetricResultPO;
import com.whatif.lab.persistence.po.ScenarioPO;
import com.whatif.lab.service.BehaviorModelService;
import com.whatif.lab.service.RuleService;
import com.whatif.lab.service.ScenarioService;
import com.whatif.lab.simulation.MetricComparison;
import com.whatif.lab.simulation.SimulationEngine;
import com.whatif.lab.simulation.snapshot.DatasetSnapshot;
import com.whatif.lab.simulation.snapshot.SnapshotCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验执行者 —— 状态机、幂等、重试、缓存全部收敛在这一个类里。
 *
 * <p><b>状态机</b>：
 * <pre>
 *   CREATED ──► PENDING ──► RUNNING ──► SUCCESS
 *                  ▲            │
 *                  └── FAILED ◄─┘   (retry < max → 回到 PENDING 重投；否则终态 FAILED)
 * </pre>
 *
 * <p><b>幂等（为什么必须有）</b>：Kafka 是至少一次投递；本地线程池也可能因为重试把同一个
 * experimentId 投两次。这里用两道门：
 * <ol>
 *   <li>入口判状态：已是 SUCCESS 直接返回已有结果，不重算；</li>
 *   <li>用<b>带条件 UPDATE 抢占</b>（{@code WHERE status IN ('CREATED','PENDING','FAILED')}）
 *       把 PENDING→RUNNING 作为「入场券」。数据库的行级原子性保证只有一个执行者能改成功，
 *       改失败的行数=0 就说明别人已经抢到了 —— 这是比「先查再改」可靠的写法
 *       （后者在并发下有竞态窗口）。</li>
 * </ol>
 *
 * <p><b>缓存与幂等的分工</b>：幂等保证「同一个实验不会被并行算两遍」；
 * 缓存的粒度更粗 —— 「参数完全相同的两个不同实验，只算一次」。
 * 前者靠状态字段，后者靠指纹哈希（见 {@link CacheKeys}），两者互不替代。
 */
@Component
public class ExperimentWorker {

    private static final Logger log = LoggerFactory.getLogger(ExperimentWorker.class);

    /** 进度推送节流：仿真内部每 10% 报一次，避免刷屏与无效 IO。 */
    private static final long PROGRESS_MIN_INTERVAL_MS = 200;

    private final ExperimentMapper experimentMapper;
    private final ExperimentTaskMapper taskMapper;
    private final ExperimentResultMapper resultMapper;
    private final MetricResultMapper metricResultMapper;
    private final ScenarioMapper scenarioMapper;
    private final DatasetMapper datasetMapper;
    private final BehaviorModelService behaviorModelService;
    private final RuleService ruleService;
    private final ScenarioService scenarioService;
    private final SnapshotCache snapshotCache;
    private final SimulationEngine engine;
    private final SingleFlight singleFlight;
    private final ProgressPublisher progressPublisher;
    private final JsonCodec json;
    private final DispatchProperties dispatchProperties;
    private final ObjectProvider<ExperimentDispatcher> dispatcherProvider;

    public ExperimentWorker(ExperimentMapper experimentMapper,
                            ExperimentTaskMapper taskMapper,
                            ExperimentResultMapper resultMapper,
                            MetricResultMapper metricResultMapper,
                            ScenarioMapper scenarioMapper,
                            DatasetMapper datasetMapper,
                            BehaviorModelService behaviorModelService,
                            RuleService ruleService,
                            ScenarioService scenarioService,
                            SnapshotCache snapshotCache,
                            SimulationEngine engine,
                            SingleFlight singleFlight,
                            ProgressPublisher progressPublisher,
                            JsonCodec json,
                            DispatchProperties dispatchProperties,
                            ObjectProvider<ExperimentDispatcher> dispatcherProvider) {
        this.experimentMapper = experimentMapper;
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.metricResultMapper = metricResultMapper;
        this.scenarioMapper = scenarioMapper;
        this.datasetMapper = datasetMapper;
        this.behaviorModelService = behaviorModelService;
        this.ruleService = ruleService;
        this.scenarioService = scenarioService;
        this.snapshotCache = snapshotCache;
        this.engine = engine;
        this.singleFlight = singleFlight;
        this.progressPublisher = progressPublisher;
        this.json = json;
        this.dispatchProperties = dispatchProperties;
        this.dispatcherProvider = dispatcherProvider;
    }

    // ------------------------------------------------------------------ 执行

    /** 执行实验（幂等：重复调用不会重复计算）。返回结果视图。 */
    public ExperimentResultView execute(Long experimentId) {
        ExperimentPO po = requireExperiment(experimentId);

        // 第一道幂等门：已经算成功了，直接把已有结果拿出来
        if ("SUCCESS".equals(po.getStatus())) {
            log.info("实验 {} 已是 SUCCESS，跳过重复执行（幂等）", experimentId);
            return view(experimentId);
        }

        // 第二道幂等门：条件 UPDATE 抢占「入场券」
        LocalDateTime now = LocalDateTime.now();
        int claimed = experimentMapper.update(null, new UpdateWrapper<ExperimentPO>()
                .eq("id", experimentId)
                .in("status", List.of("CREATED", "PENDING", "FAILED"))
                .set("status", "RUNNING")
                .set("stage", "开始执行")
                .set("started_at", now));
        if (claimed == 0) {
            log.warn("实验 {} 未能抢占（可能另一执行者正在跑），返回当前状态", experimentId);
            return view(experimentId);
        }
        updateTask(experimentId, "RUNNING", null);
        progressPublisher.publish(experimentId, "RUNNING", 1, "任务已开始");

        String fingerprint = fingerprintOf(po);
        String cacheKey = CacheKeys.experimentResult(fingerprint);
        String lockKey = CacheKeys.experimentLock(fingerprint);
        long start = System.currentTimeMillis();

        try {
            // SingleFlight：缓存命中直接返回；未命中则抢锁执行，其他人等缓存
            SingleFlight.LoadResult<ExperimentResultView> load = singleFlight.execute(
                    cacheKey, lockKey, ExperimentResultView.class,
                    () -> compute(po, fingerprint, experimentId));

            ExperimentResultView view = load.value();
            boolean cacheHit = load.cacheHit();
            /*
             * 【实测踩到的坑】缓存里存的是「第一次计算这个指纹时」生成的整份结果视图，
             * 里面带着当时的 cacheHit=false。第二个请求共享到它时，如果原样返回，
             * 调用方看到的仍然是 cacheHit=false —— 明明命中了却报未命中
             * （数据库里 cache_hit=1、耗时 36ms，而响应体说 false，两个信号自相矛盾）。
             * 所以本次请求的运行期字段必须单独覆盖：结果来自缓存，运行状态属于本次请求。
             */
            /*
             * 身份与耗时口径 —— 三点都是实测踩出来的：
             *   ① experimentId：缓存里那份视图带着"首次计算它的那次实验编号"，
             *      命中缓存时必须改写成本次新建的实验号，否则响应与库里那一行对不上（身份错误）。
             *   ② cacheHit：同理，缓存里的固定是 false。
             *   ③ elapsedMs：**统一用「本次请求的真实耗时」**，不要用 SingleFlight 内部测到的数 ——
             *      纯缓存命中路径里它是 0（只记"抢锁后执行"的耗时），界面上就会出现
             *      「缓存命中 · 0 ms」这种没人信的数字；库里 cache_hit=1 的行记的是 36~55ms。
             * 改完之后三者口径统一：界面 == 库里 == 调用方真实等待。
             */
            long elapsed = System.currentTimeMillis() - start;
            view = view.withRequestIdentity(experimentId, cacheHit, elapsed);
            if (view.experimentMeta() != null) {
                view.experimentMeta().put("cacheHit", cacheHit);
                view.experimentMeta().put("sharedWithOthers", load.sharedWithOthers());
            }
            persistResult(experimentId, view, cacheHit);
            finishSuccess(experimentId, cacheHit, System.currentTimeMillis() - start);
            progressPublisher.complete(experimentId, view);
            log.info("实验 {} 完成 cacheHit={} shared={} 耗时={}ms", experimentId, cacheHit,
                    load.sharedWithOthers(), System.currentTimeMillis() - start);
            return view;
        } catch (Throwable t) {
            return handleFailure(po, t);
        }
    }

    /** 真正的计算（不含缓存逻辑）—— 由 SingleFlight 决定是否真的跑到这里。 */
    private ExperimentResultView compute(ExperimentPO po, String fingerprint, Long experimentId) {
        DatasetPO dataset = datasetMapper.selectById(po.getDatasetId());
        ScenarioPO scenario = scenarioMapper.selectById(po.getScenarioId());
        ScenarioDsl dsl = json.read(scenario.getDsl(), ScenarioDsl.class);

        publish(experimentId, 3, "加载数据集快照");
        DatasetSnapshot snapshot = snapshotCache.get(po.getDatasetId());

        publish(experimentId, 12, "加载行为模型与规则");
        var model = behaviorModelService.modelById(po.getBehaviorModelId());
        RuleSet baselineRules = ruleService.load(po.getDatasetId(), po.getBaselineRuleVersion());
        RuleSet scenarioRules = ScenarioApplier.apply(baselineRules, dsl.changes());

        SimulationEngine.SimulationConfig cfg = new SimulationEngine.SimulationConfig(
                po.getDurationDays(), po.getSimulationCount(), po.getRandomSeed(),
                po.getActiveCustomers(), po.getCandidatesPerCustomer(), 7, 0.35, 0);

        SimulationEngine.Outcome outcome = engine.run(snapshot, model, baselineRules, scenarioRules, cfg,
                (percent, stage) -> publish(experimentId, Math.min(95, percent), stage));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fingerprint", fingerprint);
        meta.put("datasetId", po.getDatasetId());
        meta.put("datasetVersion", dataset == null ? null : dataset.getVersion());
        meta.put("scenarioId", po.getScenarioId());
        meta.put("simulationCount", po.getSimulationCount());
        meta.put("randomSeed", po.getRandomSeed());
        meta.put("activeCustomers", po.getActiveCustomers());
        meta.put("candidatesPerCustomer", po.getCandidatesPerCustomer());
        meta.put("durationDays", po.getDurationDays());
        meta.put("baselineRuleVersion", po.getBaselineRuleVersion());
        meta.put("behaviorModelId", po.getBehaviorModelId());
        meta.put("modelVersion", model.version());
        meta.put("generatedAt", LocalDateTime.now().toString());
        meta.put("reproduceHint", "同一 datasetId + modelId + ruleVersion + scenarioId + randomSeed + "
                + "simulationCount 必然得到完全相同的结果（蒙特卡洛随机流由种子决定）。");

        // 状态给 SUCCESS 而不是 RUNNING：这个对象会被写进结果缓存，
        // 而它描述的是「已经算完的结果」，不是「正在跑的任务」。
        // 曾经给 RUNNING，导致所有命中缓存的响应都回 status=RUNNING（实测踩到）。
        // 任务状态由 experiment 表与 SSE 进度负责，结果视图不承担这个职责。
        return new ExperimentResultView(experimentId, "SUCCESS", 100, "汇总完成", 
                dataset == null ? null : dataset.getName(), po.getDatasetId(),
                scenario.getName(), scenario.getNaturalLanguage(),
                baselineRules.describe(), scenarioRules.describe(),
                "LogisticRegression v" + model.version(),
                po.getRandomSeed(), po.getSimulationCount(), po.getDurationDays(),
                outcome.comparisons(), outcome.baselineSeries().summarizeByName(),
                outcome.scenarioSeries().summarizeByName(), outcome.diagnostics(), meta,
                false, 0, null);
    }

    private void publish(Long experimentId, int percent, String stage) {
        progressPublisher.publish(experimentId, "RUNNING", percent, stage);
        experimentMapper.update(null, new UpdateWrapper<ExperimentPO>()
                .eq("id", experimentId)
                .set("progress", percent)
                .set("stage", stage));
    }

    private ExperimentResultView handleFailure(ExperimentPO po, Throwable t) {
        int retry = (po.getRetryCount() == null ? 0 : po.getRetryCount()) + 1;
        boolean willRetry = retry < Math.max(1, dispatchProperties.maxRetry());
        String message = t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
        log.error("实验 {} 第 {} 次执行失败，将{}：{}", po.getId(), retry, willRetry ? "重试" : "标记为终态失败", message);

        experimentMapper.update(null, new UpdateWrapper<ExperimentPO>()
                .eq("id", po.getId())
                .set("status", willRetry ? "PENDING" : "FAILED")
                .set("retry_count", retry)
                .set("stage", willRetry ? "失败待重试" : "失败")
                .set("error_message", message)
                .set("finished_at", willRetry ? null : LocalDateTime.now()));
        updateTask(po.getId(), willRetry ? "PENDING" : "FAILED", message);
        progressPublisher.publish(po.getId(), willRetry ? "PENDING" : "FAILED", 0,
                (willRetry ? "失败待重试：" : "失败：") + message);

        if (willRetry) {
            // 重投自己（注意用 ObjectProvider 拿 dispatcher，避免 worker ↔ dispatcher 构造期循环依赖）
            ExperimentDispatcher dispatcher = dispatcherProvider.getIfAvailable();
            if (dispatcher != null) {
                dispatcher.dispatch(po.getId());
            } else {
                log.error("没有可用的调度器，实验 {} 停留在 PENDING", po.getId());
            }
        }
        return view(po.getId());
    }

    private void finishSuccess(Long experimentId, boolean cacheHit, long elapsedMs) {
        experimentMapper.update(null, new UpdateWrapper<ExperimentPO>()
                .eq("id", experimentId)
                .set("status", "SUCCESS")
                .set("stage", "完成")
                .set("progress", 100)
                .set("cache_hit", cacheHit)
                .set("elapsed_ms", elapsedMs)
                .set("finished_at", LocalDateTime.now()));
        updateTask(experimentId, "SUCCESS", null);
    }

    private void updateTask(Long experimentId, String status, String error) {
        ExperimentTaskPO task = new ExperimentTaskPO();
        task.setStatus(status);
        task.setErrorMessage(error);
        task.setWorker(Thread.currentThread().getName());
        UpdateWrapper<ExperimentTaskPO> wrapper =
                new UpdateWrapper<ExperimentTaskPO>().eq("experiment_id", experimentId);
        if ("PENDING".equals(status)) {
            // 回到 PENDING 即一次重试；用 SQL 自增而不是「读出来加一再写回」，
            // 避免两个执行者同时回写导致计数丢失（这正是我们最需要观察的指标之一）
            wrapper.setSql("retry_count = retry_count + 1");
        }
        taskMapper.update(task, wrapper);
    }

    // ------------------------------------------------------------------ 结果持久化

    /**
     * 把结果写库。
     *
     * <p>注意：<b>即使命中缓存也要写这一步</b>。缓存省掉的是「仿真计算」（秒级到分钟级 CPU），
     * 而每个实验记录都必须拥有自己的结果行 —— 否则「实验列表里点进去看不到结果」，
     * 用户完全无法理解为什么。缓存的是算，不是账。
     */
    private void persistResult(Long experimentId, ExperimentResultView view, boolean cacheHit) {
        metricResultMapper.delete(new QueryWrapper<MetricResultPO>().eq("experiment_id", experimentId));
        resultMapper.delete(new QueryWrapper<ExperimentResultPO>().eq("experiment_id", experimentId));

        for (Map.Entry<String, MetricStats> e : view.baselineMetrics().entrySet()) {
            metricResultMapper.insert(metricRow(experimentId, "BASELINE", e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, MetricStats> e : view.scenarioMetrics().entrySet()) {
            metricResultMapper.insert(metricRow(experimentId, "SCENARIO", e.getKey(), e.getValue()));
        }

        ExperimentResultPO baseline = new ExperimentResultPO();
        baseline.setExperimentId(experimentId);
        baseline.setArm("BASELINE");
        baseline.setMetrics(json.write(view.baselineMetrics()));
        resultMapper.insert(baseline);

        ExperimentResultPO scenario = new ExperimentResultPO();
        scenario.setExperimentId(experimentId);
        scenario.setArm("SCENARIO");
        scenario.setMetrics(json.write(view.scenarioMetrics()));
        resultMapper.insert(scenario);

        // 第三条：完整视图（含对比、诊断、显著性），用于无损还原 API 响应
        ExperimentResultView persisted = new ExperimentResultView(view.experimentId(), "SUCCESS", 100, "完成",
                view.datasetName(), view.datasetId(), view.scenarioName(), view.naturalLanguage(),
                view.baselineRuleSet(), view.scenarioRuleSet(), view.behaviorModelInfo(), view.randomSeed(),
                view.simulationCount(), view.durationDays(), view.comparisons(), view.baselineMetrics(),
                view.scenarioMetrics(), view.diagnostics(), view.experimentMeta(), cacheHit,
                view.elapsedMs(), null);
        ExperimentResultPO full = new ExperimentResultPO();
        full.setExperimentId(experimentId);
        full.setArm("VIEW");
        full.setMetrics(json.write(persisted));
        resultMapper.insert(full);
    }

    private MetricResultPO metricRow(Long experimentId, String arm, String metric, MetricStats s) {
        MetricResultPO po = new MetricResultPO();
        po.setExperimentId(experimentId);
        po.setArm(arm);
        po.setMetric(metric);
        po.setMeanValue(dec(s.mean()));
        po.setP5Value(dec(s.p5()));
        po.setP50Value(dec(s.p50()));
        po.setP95Value(dec(s.p95()));
        po.setStdValue(dec(s.std()));
        return po;
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ 读取

    /** 读取结果视图：优先取落库的完整视图，缺失时用两臂分布重建。 */
    public ExperimentResultView view(Long experimentId) {
        ExperimentPO po = requireExperiment(experimentId);
        ExperimentResultPO full = resultMapper.selectOne(new QueryWrapper<ExperimentResultPO>()
                .eq("experiment_id", experimentId).eq("arm", "VIEW").last("limit 1"));
        if (full != null) {
            ExperimentResultView stored = json.read(full.getMetrics(), ExperimentResultView.class);
            return new ExperimentResultView(stored.experimentId(), po.getStatus(), po.getProgress(),
                    po.getStage(), stored.datasetName(), stored.datasetId(), stored.scenarioName(),
                    stored.naturalLanguage(), stored.baselineRuleSet(), stored.scenarioRuleSet(),
                    stored.behaviorModelInfo(), stored.randomSeed(), stored.simulationCount(),
                    stored.durationDays(), stored.comparisons(), stored.baselineMetrics(),
                    stored.scenarioMetrics(), stored.diagnostics(), stored.experimentMeta(),
                    Boolean.TRUE.equals(po.getCacheHit()), po.getElapsedMs() == null ? 0 : po.getElapsedMs(),
                    po.getErrorMessage());
        }
        // 尚未产出结果：返回状态骨架（前端据此显示进度/错误）
        DatasetPO dataset = datasetMapper.selectById(po.getDatasetId());
        ScenarioPO scenario = scenarioMapper.selectById(po.getScenarioId());
        return new ExperimentResultView(experimentId, po.getStatus(), po.getProgress(), po.getStage(),
                dataset == null ? null : dataset.getName(), po.getDatasetId(),
                scenario == null ? null : scenario.getName(),
                scenario == null ? null : scenario.getNaturalLanguage(),
                null, null, null, po.getRandomSeed(), po.getSimulationCount(), po.getDurationDays(),
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Boolean.TRUE.equals(po.getCacheHit()), 0, po.getErrorMessage());
    }

    public ExperimentPO requireExperiment(Long experimentId) {
        ExperimentPO po = experimentMapper.selectById(experimentId);
        if (po == null) {
            throw BizException.notFound("实验 " + experimentId);
        }
        return po;
    }

    /**
     * 指纹：数据集 + 模型 + 基线规则版本 + 情景 DSL + 种子 + 仿真次数 + 采样口径。
     *
     * <p>少放任何一个都会造成「改了参数却命中旧缓存」的静默错误，所以这里把
     * <b>所有能改变数值结果的参数</b>都放进去 —— 宁可缓存命中率低一点，也不能算错。
     */
    public String fingerprintOf(ExperimentPO po) {
        ScenarioPO scenario = scenarioMapper.selectById(po.getScenarioId());
        ScenarioDsl dsl = scenario == null ? null : json.read(scenario.getDsl(), ScenarioDsl.class);
        List<Object> parts = new ArrayList<>();
        // 引擎版本必须进指纹：否则改了计算逻辑之后，同参数的实验会命中旧缓存、
        // 返回用旧代码算出来的结果，表现为"修复没生效"（实测踩过）
        parts.add(CacheKeys.ENGINE_VERSION);
        parts.add(po.getDatasetId());
        parts.add(po.getBehaviorModelId());
        parts.add(po.getBaselineRuleVersion());
        parts.add(dsl == null ? "no-scenario" : scenarioService.hash(dsl.canonicalForm()));
        parts.add(po.getRandomSeed());
        parts.add(po.getSimulationCount());
        parts.add(po.getActiveCustomers());
        parts.add(po.getCandidatesPerCustomer());
        parts.add(po.getDurationDays());
        parts.add("margin=" + 0.35);
        parts.add("quantile=0.70");
        parts.add("sessionInterval=7");
        return CacheKeys.fingerprint(parts);
    }

    /**
     * 【已废弃，保留只为说明坑在哪】不要把带前缀的 Redis key 落库。
     *
     * <p>experiment.cache_key 是 CHAR(64)，只够放 64 位十六进制指纹；
     * 一旦有人把 {@code CacheKeys.experimentResult(...)} 的结果写进去，
     * 就会撞上 "Data too long for column 'cache_key'"，
     * 而这个字段在创建实验的主路径上 → 整个创建流程挂掉（实测踩过）。
     * 现在创建实验一律用 {@link #fingerprintOf(ExperimentPO)}。
     */
    @Deprecated(forRemoval = false)
    public String redisKeyOf(ExperimentPO po) {
        return CacheKeys.experimentResult(fingerprintOf(po));
    }

    /** 统计信息（Dashboard 用）。 */
    public Map<String, Object> statusCounts() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String status : List.of("CREATED", "PENDING", "RUNNING", "SUCCESS", "FAILED")) {
            Long count = experimentMapper.selectCount(new QueryWrapper<ExperimentPO>().eq("status", status));
            out.put(status, count);
        }
        return out;
    }

    public List<Map<String, Object>> compare(List<Long> experimentIds) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long id : experimentIds) {
            ExperimentResultView v = view(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experimentId", id);
            row.put("status", v.status());
            row.put("scenario", v.scenarioName());
            row.put("naturalLanguage", v.naturalLanguage());
            row.put("scenarioRuleSet", v.scenarioRuleSet());
            Map<String, Object> metrics = new LinkedHashMap<>();
            if (v.scenarioMetrics() != null) {
                v.scenarioMetrics().forEach((k, s) -> metrics.put(k, s.mean()));
            }
            row.put("scenarioMeans", metrics);
            if (v.comparisons() != null) {
                Map<String, Object> changes = new LinkedHashMap<>();
                v.comparisons().forEach(c -> changes.put(c.metric().name(),
                        round(c.changeRate() * 100, 2) + "%" + (c.significant() ? "(显著)" : "(不显著)")));
                row.put("changeVsBaseline", changes);
            }
            rows.add(row);
        }
        return rows;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
