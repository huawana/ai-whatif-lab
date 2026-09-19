package com.whatif.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whatif.lab.api.dto.ExperimentResultView;
import com.whatif.lab.cache.ResultCache;
import com.whatif.lab.common.BizException;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.persistence.mapper.DatasetMapper;
import com.whatif.lab.persistence.mapper.ExperimentMapper;
import com.whatif.lab.persistence.mapper.ExperimentTaskMapper;
import com.whatif.lab.persistence.po.DatasetPO;
import com.whatif.lab.persistence.po.ExperimentPO;
import com.whatif.lab.persistence.po.ExperimentTaskPO;
import com.whatif.lab.simulation.SimulationProperties;
import com.whatif.lab.simulation.snapshot.SnapshotCache;
import com.whatif.lab.task.DispatchProperties;
import com.whatif.lab.task.ExperimentDispatcher;
import com.whatif.lab.task.ExperimentWorker;
import com.whatif.lab.task.LocalExperimentDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验服务：创建 / 查询 / 对比。
 *
 * <p><b>同步还是异步，是调用方选的，不是实现定的</b>。
 * 同步模式（{@code sync=true}）直接在当前线程把仿真跑完并返回完整结果 ——
 * 用于脚本、验证、以及「就想立刻看结果」的场景；
 * 异步模式立即返回 {@code PENDING} + experimentId，由调度器在后台跑，进度走 SSE。
 * 两条路径<b>共用同一个 worker</b>，所以结果、缓存、幂等行为完全一致，
 * 不存在「同步跑一套逻辑、异步跑另一套」的经典分叉。
 */
@Service
public class ExperimentService {

    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);

    private final ExperimentMapper experimentMapper;
    private final ExperimentTaskMapper taskMapper;
    private final DatasetMapper datasetMapper;
    private final ScenarioService scenarioService;
    private final BehaviorModelService behaviorModelService;
    private final RuleService ruleService;
    private final SnapshotCache snapshotCache;
    private final ExperimentWorker worker;
    private final ObjectProvider<ExperimentDispatcher> dispatcherProvider;
    private final SimulationProperties simulationProperties;
    private final DispatchProperties dispatchProperties;
    private final ResultCache resultCache;

    public ExperimentService(ExperimentMapper experimentMapper,
                             ExperimentTaskMapper taskMapper,
                             DatasetMapper datasetMapper,
                             ScenarioService scenarioService,
                             BehaviorModelService behaviorModelService,
                             RuleService ruleService,
                             SnapshotCache snapshotCache,
                             ExperimentWorker worker,
                             ObjectProvider<ExperimentDispatcher> dispatcherProvider,
                             SimulationProperties simulationProperties,
                             DispatchProperties dispatchProperties,
                             ResultCache resultCache) {
        this.experimentMapper = experimentMapper;
        this.taskMapper = taskMapper;
        this.datasetMapper = datasetMapper;
        this.scenarioService = scenarioService;
        this.behaviorModelService = behaviorModelService;
        this.ruleService = ruleService;
        this.snapshotCache = snapshotCache;
        this.worker = worker;
        this.dispatcherProvider = dispatcherProvider;
        this.simulationProperties = simulationProperties;
        this.dispatchProperties = dispatchProperties;
        this.resultCache = resultCache;
    }

    /** 创建实验的入参（DSL 与 scenarioId 二选一；dsl 优先）。 */
    public record CreateRequest(Long datasetId,
                               Long scenarioId,
                               ScenarioDsl dsl,
                               String naturalLanguage,
                               Integer simulations,
                               Integer durationDays,
                               Long seed,
                               Integer activeCustomers,
                               Integer candidatesPerCustomer,
                               String source,
                               boolean sync) {
    }

    public ExperimentResultView create(CreateRequest req) {
        DatasetPO dataset = requireReadyDataset(req.datasetId());
        var model = behaviorModelService.requireActivePO(dataset.getId());
        int ruleVersion = ruleService.currentVersion(dataset.getId(), dataset.getBaselineRuleVersion());

        Long scenarioId = req.scenarioId();
        if (req.dsl() != null) {
            ScenarioService.ValidationResult validation =
                    scenarioService.validate(dataset.getId(), req.dsl(), ruleVersion);
            var saved = scenarioService.save(dataset.getId(), req.dsl(), req.naturalLanguage(),
                    req.source() == null ? "MANUAL" : req.source(), validation);
            scenarioId = saved.getId();
            log.info("情景已创建 id={} source={} 改动={}", scenarioId, saved.getSource(),
                    req.dsl().changes().size());
        } else if (scenarioId == null) {
            throw BizException.validation("必须提供 dsl 或 scenarioId（提供空 changes 的 dsl 表示恒等实验）");
        } else {
            scenarioService.get(scenarioId);   // 存在性校验
        }

        ExperimentPO po = new ExperimentPO();
        po.setDatasetId(dataset.getId());
        po.setScenarioId(scenarioId);
        po.setBehaviorModelId(model.getId());
        po.setBaselineRuleVersion(ruleVersion);
        po.setScenarioDslHash(scenarioService.hash(scenarioService.dslOf(scenarioService.get(scenarioId))
                .canonicalForm()));
        po.setDomain(dataset.getDomain());
        po.setDurationDays(clamp(req.durationDays(), simulationProperties.defaultDurationDays(), 1, 365));
        po.setSimulationCount(clamp(req.simulations(), simulationProperties.defaultSimulations(),
                1, simulationProperties.maxSimulations()));
        po.setRandomSeed(req.seed() == null ? 20260919L : req.seed());
        po.setActiveCustomers(clamp(req.activeCustomers(), simulationProperties.activeCustomers(),
                1, simulationProperties.maxActiveCustomers()));
        po.setCandidatesPerCustomer(clamp(req.candidatesPerCustomer(),
                simulationProperties.candidatesPerCustomer(), 1, 64));
        po.setStatus("CREATED");
        po.setStage("已创建");
        po.setProgress(0);
        po.setCacheHit(false);
        po.setRetryCount(0);
        experimentMapper.insert(po);
        // 【实测踩到的真 bug】这里曾经写的是 worker.cacheKeyOf(po)，
        // 那是拼好的 Redis key（"whatif:experiment:result:" + 64 位哈希 ≈ 89 字符），
        // 而 experiment.cache_key 是 CHAR(64) → "Data too long for column 'cache_key'"，
        // 因为这一行在创建实验的主路径上，结果是**任何实验都创建不出来**。
        // 正确做法：库里只存 64 位纯指纹（幂等/等价判断用它），
        // 带前缀的 Redis key 只在访问缓存时现拼（见 CacheKeys）。
        po.setCacheKey(worker.fingerprintOf(po));
        experimentMapper.updateById(po);

        ExperimentTaskPO task = new ExperimentTaskPO();
        task.setExperimentId(po.getId());
        task.setStatus("PENDING");
        task.setRetryCount(0);
        task.setMaxRetry(dispatchProperties.maxRetry());
        taskMapper.insert(task);

        if (req.sync()) {
            // 同步路径：不经过调度器，直接执行（结果与异步完全相同）
            return worker.execute(po.getId());
        }
        ExperimentDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null) {
            throw new BizException(BizException.CONFLICT, "没有可用的实验调度器");
        }
        dispatcher.dispatch(po.getId());
        return worker.view(po.getId());
    }

    public ExperimentResultView view(Long experimentId) {
        return worker.view(experimentId);
    }

    public List<Map<String, Object>> list(Long datasetId, String status, int limit) {
        List<ExperimentPO> rows = experimentMapper.selectList(new QueryWrapper<ExperimentPO>()
                .eq(datasetId != null, "dataset_id", datasetId)
                .eq(status != null && !status.isBlank(), "status", status)
                .orderByDesc("id")
                .last("limit " + Math.max(1, Math.min(limit, 200))));
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ExperimentPO po : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("experimentId", po.getId());
            m.put("datasetId", po.getDatasetId());
            m.put("scenarioId", po.getScenarioId());
            m.put("status", po.getStatus());
            m.put("stage", po.getStage());
            m.put("progress", po.getProgress());
            m.put("simulationCount", po.getSimulationCount());
            m.put("randomSeed", po.getRandomSeed());
            m.put("cacheHit", po.getCacheHit());
            m.put("cacheKey", po.getCacheKey());
            m.put("retryCount", po.getRetryCount());
            m.put("elapsedMs", po.getElapsedMs());
            m.put("createdAt", String.valueOf(po.getCreatedAt()));
            m.put("errorMessage", po.getErrorMessage());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> compare(List<Long> experimentIds) {
        if (experimentIds == null || experimentIds.isEmpty()) {
            throw BizException.validation("请提供至少一个 experimentId");
        }
        return worker.compare(experimentIds);
    }

    /** Dashboard 统计。 */
    public Map<String, Object> dashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> counts = worker.statusCounts();
        long total = counts.values().stream().mapToLong(v -> (Long) v).sum();
        out.put("experimentTotal", total);
        out.put("byStatus", counts);
        out.put("cache", resultCache.stats());
        out.put("cachedDatasets", snapshotCache.cachedDatasets());
        ExperimentDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        out.put("dispatchMode", dispatcher == null ? "none" : dispatcher.mode());
        if (dispatcher instanceof LocalExperimentDispatcher local) {
            out.put("localQueueSize", local.queueSize());
            out.put("localActiveWorkers", local.activeCount());
        }
        out.put("datasets", datasetMapper.selectCount(null));
        return out;
    }

    private DatasetPO requireReadyDataset(Long datasetId) {
        if (datasetId == null) {
            throw BizException.validation("datasetId 不能为空");
        }
        DatasetPO po = datasetMapper.selectById(datasetId);
        if (po == null) {
            throw BizException.notFound("数据集 " + datasetId);
        }
        if (!"READY".equals(po.getStatus())) {
            throw BizException.validation("数据集 " + datasetId + " 当前状态为 " + po.getStatus()
                    + "，只有 READY 的数据集才能创建实验");
        }
        return po;
    }

    private static int clamp(Integer value, int defaultValue, int min, int max) {
        int v = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, v));
    }
}
