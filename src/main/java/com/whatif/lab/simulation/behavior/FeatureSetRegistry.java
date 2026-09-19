package com.whatif.lab.simulation.behavior;

import com.whatif.lab.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征集注册表 —— 「平台支持哪些特征」这件事的唯一入口。
 *
 * <p><b>为什么需要"按特征名反查"</b>（{@link #byFeatureNames}）：
 * 模型落库时存的是它训练时用的特征名列表，系数是按那个顺序对齐的。
 * 仿真时必须用**名字完全一致**的特征集来解释这个模型 —— 否则系数会静默错位
 * （例如把消费额的系数乘到购买频次上），算出来的概率毫无意义却不报错。
 * 所以这里是"要么精确匹配、要么响亮报错"，没有"差不多就行"。
 *
 * <p>这也解释了「改了 features/ 配置之后必须重新训练模型」：新配置的特征名/顺序变了，
 * 老模型就对不上任何特征集 —— 此时接口会明确要求重训，而不是拿老系数硬算。
 */
@Component
public class FeatureSetRegistry {

    private static final Logger log = LoggerFactory.getLogger(FeatureSetRegistry.class);

    private final Map<String, FeatureSet> byName = new LinkedHashMap<>();
    private final Map<String, String> problems;

    public FeatureSetRegistry(FeatureSetLoader loader) {
        FeatureSetLoader.Loaded loaded = loader.load();
        for (FeatureSetSpec spec : loaded.specs()) {
            byName.put(spec.featureSet().toLowerCase(), new FeatureSet(spec));
        }
        this.problems = loaded.problems();
        // 内置默认特征集缺失 = 打包问题（classpath:features/*.json 没打进去），必须响亮地失败
        if (!byName.containsKey(FeatureSetSpec.DEFAULT_NAME)) {
            throw new IllegalStateException("内置特征集 " + FeatureSetSpec.DEFAULT_NAME
                    + " 缺失：请确认 src/main/resources/features/" + FeatureSetSpec.DEFAULT_NAME + ".json 已打包");
        }
        log.info("已注册特征集: {}（默认 {}，维度 {}）", byName.keySet(), FeatureSetSpec.DEFAULT_NAME,
                byName.get(FeatureSetSpec.DEFAULT_NAME).dimension());
    }

    public FeatureSet require(String name) {
        FeatureSet fs = byName.get(name == null ? "" : name.toLowerCase());
        if (fs == null) {
            String hint = problems.isEmpty() ? "" : "；注意有配置未生效: " + problems.keySet();
            throw BizException.validation("不存在的特征集: " + name + "，当前可用: " + byName.keySet() + hint);
        }
        return fs;
    }

    /** 默认特征集（老数据集/老模型都用它，保证向后兼容）。 */
    public FeatureSet defaultSet() {
        return require(FeatureSetSpec.DEFAULT_NAME);
    }

    /**
     * 按特征名列表反查特征集（用于"解释一个已落库的模型"）。
     *
     * <p>必须精确匹配（名字与顺序都一样）。匹配不到就报错，绝不"按维度凑合"。
     */
    public FeatureSet byFeatureNames(List<String> names) {
        for (FeatureSet fs : byName.values()) {
            if (fs.names().equals(names)) {
                return fs;
            }
        }
        StringBuilder available = new StringBuilder();
        byName.values().forEach(fs -> available.append(fs.name()).append("(").append(fs.names()).append(") "));
        throw BizException.validation("模型训练时用的特征名对不上任何已注册的特征集，无法解释它的系数："
                + "模型=" + names + "；可用特征集=" + available
                + "。若你刚改过 features/ 配置，请重新训练该数据集的模型。");
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /** 接口回显：每个特征集长什么样、哪些特征能被预计算。 */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (FeatureSet fs : byName.values()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("dimension", fs.dimension());
            one.put("features", fs.names());
            one.put("scopeBreakdown", fs.scopeBreakdown());
            one.put("description", fs.spec().description());
            out.put(fs.name(), one);
        }
        return out;
    }

    public Map<String, String> problems() {
        return problems;
    }

    /** 所有特征名（用于接口/文档展示）。 */
    public List<String> allFeatureNames() {
        List<String> out = new ArrayList<>();
        byName.values().forEach(fs -> out.addAll(fs.names()));
        return out;
    }
}
