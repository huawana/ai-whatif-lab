package com.whatif.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.domain.scenario.ScenarioApplier;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.persistence.mapper.ScenarioMapper;
import com.whatif.lab.persistence.po.ScenarioPO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情景服务：Scenario DSL 的校验、落库、以及「基线 + 改动 → 情景规则集」。
 *
 * <p>校验分三层，缺一不可（对应文档第十二节「后端不直接相信 LLM」）：
 * <ol>
 *   <li><b>结构校验</b>（{@link ScenarioDsl#validateBasics()}）：必填字段在不在；</li>
 *   <li><b>语义校验</b>（{@link ScenarioApplier}）：规则类型/字段名是不是真实存在的；</li>
 *   <li><b>业务校验</b>（{@link RuleService#validate}）：数值在业务上说得通吗
 *       （折扣不能大于门槛、比例必须落在 0~1）。</li>
 * </ol>
 * 三层都过不了就不允许创建实验 —— 让非法情景在入口处失败，而不是在仿真里产生一个漂亮但错的结果。
 */
@Service
public class ScenarioService {

    private final ScenarioMapper scenarioMapper;
    private final RuleService ruleService;
    private final JsonCodec json;

    public ScenarioService(ScenarioMapper scenarioMapper, RuleService ruleService, JsonCodec json) {
        this.scenarioMapper = scenarioMapper;
        this.ruleService = ruleService;
        this.json = json;
    }

    /** 校验结果（校验通过时 scenarioRules 非空）。 */
    public record ValidationResult(boolean valid,
                                   String message,
                                   RuleSet baselineRules,
                                   RuleSet scenarioRules,
                                   List<String> warnings,
                                   String dslHash,
                                   String canonicalForm) {
    }

    /**
     * 完整校验：结构 → 语义 → 业务。
     *
     * <p>返回的 {@code warnings} 是「合法但值得提醒」的情况（例如折扣力度超过 50%），
     * 它们不会阻断流程，但会原样返回给调用方与 AI 解释 —— 让风险可见比直接拒绝更有用。
     */
    public ValidationResult validate(Long datasetId, ScenarioDsl dsl, Integer baselineVersion) {
        dsl.validateBasics();
        int version = ruleService.currentVersion(datasetId, baselineVersion);
        RuleSet baseline = ruleService.load(datasetId, version);

        RuleSet scenarioRules = ScenarioApplier.apply(baseline, dsl.changes());
        ruleService.validate(scenarioRules.rules());

        List<String> warnings = new java.util.ArrayList<>();
        double discount = scenarioRules.ofType(com.whatif.lab.domain.rule.RuleType.DISCOUNT).stream()
                .mapToDouble(r -> r.getDouble("threshold", 0))
                .min().orElse(0);
        if (discount > 0 && discount < 50) {
            warnings.add("优惠门槛较低(" + discount + "元)，可能带来大量小额订单与不可控的优惠成本");
        }
        for (var r : scenarioRules.ofType(com.whatif.lab.domain.rule.RuleType.PRICE)) {
            double m = r.getDouble("multiply", 1.0);
            if (m < 0.7) {
                warnings.add("价格系数 " + m + " 意味着降价超过 30%，属于超出历史观测区间的外推，结论需谨慎");
            }
        }

        return new ValidationResult(true, "校验通过", baseline, scenarioRules, warnings,
                hash(dsl.canonicalForm()), dsl.canonicalForm());
    }

    /** 落库一个情景（source = AI / MANUAL）。 */
    public ScenarioPO save(Long datasetId, ScenarioDsl dsl, String naturalLanguage, String source,
                           ValidationResult validation) {
        ScenarioPO po = new ScenarioPO();
        po.setDatasetId(datasetId);
        po.setName(dsl.name() == null || dsl.name().isBlank() ? ("情景 " + System.currentTimeMillis()) : dsl.name());
        po.setNaturalLanguage(naturalLanguage);
        po.setDsl(json.write(dsl));
        po.setDurationDays(dsl.durationDays() == null ? 30 : dsl.durationDays());
        po.setSource(source);
        po.setStatus(validation == null || !validation.valid() ? "INVALID" : "VALIDATED");
        po.setValidationMessage(validation == null ? null : validation.message());
        po.setDslHash(hash(dsl.canonicalForm()));
        scenarioMapper.insert(po);
        return po;
    }

    public ScenarioPO get(Long scenarioId) {
        ScenarioPO po = scenarioMapper.selectById(scenarioId);
        if (po == null) {
            throw BizException.notFound("情景 " + scenarioId);
        }
        return po;
    }

    /** 还原 DSL（用于重放/展示）。 */
    public ScenarioDsl dslOf(ScenarioPO po) {
        return json.read(po.getDsl(), ScenarioDsl.class);
    }

    public List<ScenarioPO> list(Long datasetId, int limit) {
        return scenarioMapper.selectList(new QueryWrapper<ScenarioPO>()
                .eq(datasetId != null, "dataset_id", datasetId)
                .orderByDesc("id").last("limit " + Math.max(1, Math.min(limit, 200))));
    }

    /** DSL 归一化后的 SHA-256 —— 同时用作缓存键的组成与实验快照里的情景标识。 */
    public static String hash(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 情景摘要（给 AI 工具与前端用）。 */
    public Map<String, Object> describe(ScenarioPO po, ScenarioDsl dsl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scenarioId", po.getId());
        m.put("name", po.getName());
        m.put("datasetId", po.getDatasetId());
        m.put("durationDays", po.getDurationDays());
        m.put("source", po.getSource());
        m.put("status", po.getStatus());
        m.put("naturalLanguage", po.getNaturalLanguage());
        m.put("dsl", dsl);
        m.put("changes", dsl.changes().stream().map(c ->
                c.ruleType() + "." + c.field() + " : " + c.from() + " → " + c.to()).toList());
        return m;
    }
}
