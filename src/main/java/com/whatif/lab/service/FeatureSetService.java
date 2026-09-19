package com.whatif.lab.service;

import com.whatif.lab.data.extract.DomainExtractorRegistry;
import com.whatif.lab.persistence.mapper.DatasetMapper;
import com.whatif.lab.persistence.po.DatasetPO;
import com.whatif.lab.simulation.behavior.FeatureSet;
import com.whatif.lab.simulation.behavior.FeatureSetRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 特征集解析服务 —— 把「数据集 → 用哪个特征集」这条链路收在一处。
 *
 * <p><b>链路</b>：数据集行 → 它的 {@code domain} → 该领域配置里的 {@code features} 名
 * → {@link FeatureSetRegistry} 里的特征集。领域没配置文件（手写实现）就用默认特征集，
 * 所以"老数据集继续可用"是天然成立的，不需要额外兼容分支。
 *
 * <p><b>为什么要在服务层再包一层，而不是各调用点自己查</b>：
 * 「训练用哪套特征」与「仿真解释哪个模型」必须完全一致 —— 分散查询迟早会出现
 * 训练用 A、仿真按 B 解释，而症状是"概率莫名不对"（系数错位），极难排查。
 */
@Service
public class FeatureSetService {

    private final DatasetMapper datasetMapper;
    private final DomainExtractorRegistry domains;
    private final FeatureSetRegistry featureSets;

    public FeatureSetService(DatasetMapper datasetMapper,
                             DomainExtractorRegistry domains,
                             FeatureSetRegistry featureSets) {
        this.datasetMapper = datasetMapper;
        this.domains = domains;
        this.featureSets = featureSets;
    }

    /** 该数据集训练/仿真应该用的特征集。 */
    public FeatureSet forDataset(Long datasetId) {
        DatasetPO po = datasetMapper.selectById(datasetId);
        String domain = po == null ? null : po.getDomain();
        return featureSets.require(domains.featureSetOf(domain));
    }

    /** 该数据集用的特征集名（供接口展示）。 */
    public String nameForDataset(Long datasetId) {
        return forDataset(datasetId).name();
    }

    /** 全平台可用的特征集清单（配置状态 + 每个特征集的作用域分解）。 */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default", com.whatif.lab.simulation.behavior.FeatureSetSpec.DEFAULT_NAME);
        out.put("featureSets", featureSets.describe());
        out.put("problems", featureSets.problems());
        return out;
    }
}
