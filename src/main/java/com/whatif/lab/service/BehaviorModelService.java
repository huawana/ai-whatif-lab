package com.whatif.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.persistence.mapper.BehaviorModelMapper;
import com.whatif.lab.persistence.po.BehaviorModelPO;
import com.whatif.lab.simulation.SimulationProperties;
import com.whatif.lab.simulation.behavior.BehaviorModel;
import com.whatif.lab.simulation.behavior.FeatureSet;
import com.whatif.lab.simulation.behavior.LogisticRegressionTrainer;
import com.whatif.lab.simulation.behavior.TrainingSetBuilder;
import com.whatif.lab.simulation.snapshot.DatasetSnapshot;
import com.whatif.lab.simulation.snapshot.SnapshotCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行为模型服务：训练 / 加载 / 缓存。
 *
 * <p>模型按 (datasetId, version) 版本化，`active` 标记当前生效版本。
 * 训练是确定性的（固定种子 + 全批量梯度下降），所以「同一数据集 + 同一版本 + 同一种子」
 * 必然得到同一套权重 —— 这是实验结果可复现的前提之一。
 */
@Service
public class BehaviorModelService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorModelService.class);

    private final BehaviorModelMapper mapper;
    private final TrainingSetBuilder trainingSetBuilder;
    private final SnapshotCache snapshotCache;
    private final SimulationProperties properties;
    private final JsonCodec json;
    private final RuleService ruleService;
    private final FeatureSetService featureSets;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** datasetId → 已还原的模型对象（避免每次实验都反序列化 + 查库）。 */
    private final Map<Long, BehaviorModel> modelCache = new ConcurrentHashMap<>();

    public BehaviorModelService(BehaviorModelMapper mapper,
                                TrainingSetBuilder trainingSetBuilder,
                                SnapshotCache snapshotCache,
                                SimulationProperties properties,
                                JsonCodec json,
                                RuleService ruleService,
                                FeatureSetService featureSets,
                                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.mapper = mapper;
        this.trainingSetBuilder = trainingSetBuilder;
        this.snapshotCache = snapshotCache;
        this.properties = properties;
        this.json = json;
        this.ruleService = ruleService;
        this.featureSets = featureSets;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TrainResult(BehaviorModelPO model, Map<String, Object> trainingStats, long elapsedMs) {
    }

    /** 训练并落库新版本模型。 */
    public TrainResult train(Long datasetId, Integer baselineRuleVersion, Integer negativeRatio, Long seed,
                             LogisticRegressionTrainer.Config config) {
        long start = System.currentTimeMillis();
        int ratio = negativeRatio == null ? properties.negativeRatio() : negativeRatio;
        long useSeed = seed == null ? 20260919L : seed;

        DatasetSnapshot snapshot = snapshotCache.get(datasetId);
        RuleSet baseline = ruleSetForDiscountFeature(datasetId, baselineRuleVersion, snapshot);
        // 该数据集用哪套特征集 —— 由它的领域配置决定（未配置则默认），训练与仿真共用同一个特征集
        FeatureSet featureSet = featureSets.forDataset(datasetId);
        TrainingSetBuilder.TrainingSet set = trainingSetBuilder.build(snapshot, featureSet, baseline, ratio, useSeed);
        LogisticRegressionTrainer.Result fit = LogisticRegressionTrainer.fit(set.features(), set.labels(),
                config == null ? LogisticRegressionTrainer.Config.defaults() : config);

        BehaviorModelPO po = new BehaviorModelPO();
        po.setDatasetId(datasetId);
        po.setVersion(nextVersion(datasetId));
        po.setModelType("LOGISTIC_REGRESSION");
        po.setFeatureNames(json.write(featureSet.names()));
        po.setCoefficients(json.write(toList(fit.coefficients())));
        po.setIntercept(BigDecimal.valueOf(fit.intercept()));
        po.setFeatureMeans(json.write(toList(fit.means())));
        po.setFeatureStds(json.write(toList(fit.stds())));
        Map<String, Object> metrics = new LinkedHashMap<>(fit.metrics());
        metrics.put("trainingStats", set.stats());
        po.setTrainMetrics(json.write(metrics));
        po.setTrainSampleCount(set.features().length);
        po.setPositiveCount(set.labels().length - countZeros(set.labels()));
        po.setTrainingSeed(useSeed);
        po.setActive(true);
        mapper.insert(po);

        // 旧版本置为非活跃（保留历史，便于复现历史实验）
        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BehaviorModelPO>()
                .eq("dataset_id", datasetId).ne("id", po.getId()).set("active", false));
        modelCache.remove(datasetId);

        long elapsed = System.currentTimeMillis() - start;
        log.info("模型训练完成 datasetId={} version={} 样本={} 正样本={} AUC={} LogLoss={} 耗时={}ms",
                datasetId, po.getVersion(), set.features().length, set.labels().length,
                metrics.get("auc"), metrics.get("logLoss"), elapsed);
        return new TrainResult(po, set.stats(), elapsed);
    }

    /** 当前生效模型（带进程内缓存）。 */
    public BehaviorModel activeModel(Long datasetId) {
        return modelCache.computeIfAbsent(datasetId, id -> BehaviorModel.fromPO(requireActivePO(id), json));
    }

    public BehaviorModel modelById(Long modelId) {
        BehaviorModelPO po = mapper.selectById(modelId);
        if (po == null) {
            throw BizException.notFound("行为模型 " + modelId);
        }
        return BehaviorModel.fromPO(po, json);
    }

    public BehaviorModelPO requireActivePO(Long datasetId) {
        BehaviorModelPO po = mapper.selectOne(new QueryWrapper<BehaviorModelPO>()
                .eq("dataset_id", datasetId).eq("active", true).orderByDesc("version").last("limit 1"));
        if (po == null) {
            throw BizException.validation("数据集 " + datasetId + " 还没有行为模型，请先训练模型："
                    + "POST /api/datasets/" + datasetId + "/model/train");
        }
        return po;
    }

    public List<Map<String, Object>> listModels(Long datasetId) {
        List<BehaviorModelPO> rows = mapper.selectList(new QueryWrapper<BehaviorModelPO>()
                .eq("dataset_id", datasetId).orderByDesc("version"));
        return rows.stream().map(po -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modelId", po.getId());
            m.put("version", po.getVersion());
            m.put("type", po.getModelType());
            m.put("active", po.getActive());
            m.put("samples", po.getTrainSampleCount());
            m.put("positives", po.getPositiveCount());
            m.put("trainedAt", String.valueOf(po.getTrainedAt()));
            m.put("trainingSeed", po.getTrainingSeed());
            m.put("metrics", json.readMap(po.getTrainMetrics()));
            BehaviorModel model = BehaviorModel.fromPO(po, json);
            m.put("weights", model.weightRanking());
            return m;
        }).toList();
    }

    /** 模型是否与当前特征口径兼容（特征增删后旧模型必须重训）。 */
    public Map<String, Object> compatibility(BehaviorModelPO po) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> names = json.readStringList(po.getFeatureNames());
        List<String> expected = featureSets.forDataset(po.getDatasetId()).names();
        m.put("modelFeatures", names);
        m.put("datasetFeatureSet", featureSets.nameForDataset(po.getDatasetId()));
        m.put("expectedFeatures", expected);
        m.put("compatible", names.equals(expected));
        return m;
    }

    /** 取「训练时该用的规则集」：折扣特征必须以当时生效的规则为准（见 TrainingSetBuilder）。 */
    private RuleSet ruleSetForDiscountFeature(Long datasetId, Integer ruleVersion, DatasetSnapshot snapshot) {
        int version = ruleService.currentVersion(datasetId, ruleVersion);
        return ruleService.load(datasetId, version);
    }

    /** 下一个模型版本号。理由同 RuleService#nextVersion：聚合查询交给 JdbcTemplate，别用 ORM 的 select(max)。 */
    private int nextVersion(Long datasetId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM behavior_model WHERE dataset_id = ?",
                Integer.class, datasetId);
        return (max == null ? 0 : max) + 1;
    }

    private static List<Double> toList(double[] values) {
        List<Double> list = new java.util.ArrayList<>(values.length);
        for (double v : values) {
            list.add(v);
        }
        return list;
    }

    private static int countZeros(int[] labels) {
        int c = 0;
        for (int v : labels) {
            if (v == 0) {
                c++;
            }
        }
        return c;
    }
}
