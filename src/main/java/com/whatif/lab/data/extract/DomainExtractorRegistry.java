package com.whatif.lab.data.extract;

import com.whatif.lab.common.BizException;
import com.whatif.lab.simulation.behavior.FeatureSetSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域注册表：把「写死实现」与「配置驱动实现」合成同一张表，调用方不需要区分。
 *
 * <p>两类来源：
 * <ol>
 *   <li>Spring 注入的 {@link DomainExtractor} 实现（本项目自带的 {@code EcommerceExtractor}）；</li>
 *   <li>{@link DomainSpecLoader} 从内置/外部目录加载的配置，每个配置实例化一个 {@link GenericExtractor}。</li>
 * </ol>
 * 同名时**配置覆盖内置**（使用者的意图优先，并记一条 INFO 日志）——
 * 这正是"新增领域不用改代码"的落点：丢一份 JSON 进去就能用，甚至能覆盖内置行为。
 *
 * <p>配置里写错的地方不静默：{@link #problems()} 会把每份坏配置的原因暴露给接口
 * （{@code GET /api/datasets/domains}），否则使用者只会看到"领域不存在"，无从排查。
 */
@Component
public class DomainExtractorRegistry {

    private static final Logger log = LoggerFactory.getLogger(DomainExtractorRegistry.class);

    private final Map<String, DomainExtractor> byDomain = new LinkedHashMap<>();
    private final Map<String, String> problems;

    public DomainExtractorRegistry(List<DomainExtractor> extractors, DomainSpecLoader loader) {
        for (DomainExtractor e : extractors) {
            byDomain.put(e.domain().toLowerCase(), e);
        }
        DomainSpecLoader.Loaded loaded = loader.load();
        for (DomainSpec spec : loaded.specs()) {
            String key = spec.domain().toLowerCase();
            if (byDomain.containsKey(key)) {
                log.info("领域 {} 由配置覆盖了内置实现（配置文件优先）", key);
            }
            byDomain.put(key, new GenericExtractor(spec));
        }
        this.problems = loaded.problems();
        log.info("已注册领域: {}（其中配置驱动 {} 个）", byDomain.keySet(), loaded.specs().size());
    }

    public DomainExtractor require(String domain) {
        DomainExtractor e = byDomain.get(domain == null ? "" : domain.toLowerCase());
        if (e == null) {
            String hint = problems.isEmpty() ? ""
                    : "；注意有配置未生效: " + problems.keySet();
            throw BizException.validation("不支持的领域: " + domain + "，当前已注册: " + byDomain.keySet() + hint);
        }
        return e;
    }

    public List<String> domains() {
        return List.copyOf(byDomain.keySet());
    }

    /**
     * 该领域应该用哪个特征集。
     *
     * <p>配置驱动的领域可以自己指定；手写实现（{@code ecommerce}）没有配置文件 → 用默认特征集。
     * 这样"老领域继续可用"是天然的，不需要额外兼容分支。
     */
    public String featureSetOf(String domain) {
        DomainSpec spec = specs().get(domain == null ? "" : domain.toLowerCase());
        return spec == null ? FeatureSetSpec.DEFAULT_NAME : spec.features();
    }

    /** 配置问题（文件名 → 原因）。空表示所有配置都正常。 */
    public Map<String, String> problems() {
        return problems;
    }

    /** 配置驱动的领域对应的原始配置（用于接口回显，让使用者能核对"生效的到底是什么"）。 */
    public Map<String, DomainSpec> specs() {
        Map<String, DomainSpec> out = new LinkedHashMap<>();
        byDomain.forEach((k, v) -> {
            if (v instanceof GenericExtractor g) {
                out.put(k, g.spec());
            }
        });
        return out;
    }
}
