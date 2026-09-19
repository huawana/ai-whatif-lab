package com.whatif.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.domain.rule.RuleSpec;
import com.whatif.lab.domain.rule.RuleType;
import com.whatif.lab.persistence.mapper.RuleMapper;
import com.whatif.lab.persistence.po.RulePO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则集服务：规则集的版本化 CRUD。
 *
 * <p><b>规则集是 Baseline 的定义</b>。实验里「Scenario vs Baseline」的 Baseline
 * 就是这个数据集当前生效的那一版规则集 —— 它不是「历史数据里真实存在的规则」
 * （UCI 数据里没有优惠字段），而是<b>用户声明的当前业务规则</b>。
 * 所以规则集必须可改、可版本化、可追溯到实验快照上：
 * 否则「上次那个结果的 Baseline 是什么」将永远无法回答。
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final RuleMapper ruleMapper;
    private final JsonCodec json;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RuleService(RuleMapper ruleMapper, JsonCodec json,
                       org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.ruleMapper = ruleMapper;
        this.json = json;
        this.jdbc = jdbc;
    }

    /**
     * 为新建数据集写入默认基线规则集（version 1）。
     *
     * <p>默认值「满 100 减 10」是<b>显式假设</b>，不是从数据里学来的 ——
     * UCI Online Retail 没有优惠字段。之所以给这个默认值：
     * ① 让平台第一次就有可对比的基线；② 让 AI 能听懂「改成满 80 减 15」这种相对改动。
     * 用户可以在规则管理里改成任何值，改完就是新的版本号。
     */
    @Transactional
    public void createDefaultBaseline(Long datasetId) {
        List<RuleSpec> rules = new ArrayList<>();
        rules.add(new RuleSpec(RuleType.DISCOUNT,
                Map.of("threshold", 100, "amount", 10),
                "当前优惠规则：满100减10（假设值，代表业务现状，可在规则管理中修改）"));
        rules.add(new RuleSpec(RuleType.PRICE,
                Map.of("multiply", 1.0),
                "无价格调整（EffectivePrice = 挂牌价 × 1.0）"));
        rules.add(new RuleSpec(RuleType.PROBABILITY,
                Map.of("multiplier", 1.0),
                "无购买概率干预"));
        save(datasetId, "当前业务规则(默认基线)", rules);
    }

    /** 保存一套规则集，返回新版本号。 */
    @Transactional
    public int save(Long datasetId, String setName, List<RuleSpec> rules) {
        if (rules == null || rules.isEmpty()) {
            throw BizException.validation("规则集不能为空");
        }
        validate(rules);
        int version = nextVersion(datasetId);
        for (RuleSpec spec : rules) {
            RulePO po = new RulePO();
            po.setDatasetId(datasetId);
            po.setVersion(version);
            po.setSetName(setName);
            po.setRuleType(spec.type().name());
            po.setParams(json.write(spec.params()));
            po.setDescription(spec.description());
            ruleMapper.insert(po);
        }
        log.info("规则集已保存 datasetId={} version={} setName={} rules={}", datasetId, version, setName, rules.size());
        return version;
    }

    /** 业务校验：拦住物理上不可能的规则（负数门槛、>100% 折扣）。这是 DSL 校验的第二层。 */
    public void validate(List<RuleSpec> rules) {
        for (RuleSpec spec : rules) {
            switch (spec.type()) {
                case DISCOUNT -> {
                    double threshold = spec.getDouble("threshold", 100);
                    if (threshold < 0) {
                        throw BizException.validation("优惠门槛不能为负: " + threshold);
                    }
                    if (spec.has("percent")) {
                        double percent = spec.getDouble("percent", 0);
                        if (percent < 0 || percent > 1) {
                            throw BizException.validation("折扣比例必须在 [0,1] 之间: " + percent);
                        }
                    } else {
                        double amount = spec.getDouble("amount", 0);
                        if (amount < 0) {
                            throw BizException.validation("优惠金额不能为负: " + amount);
                        }
                        if (amount > threshold && threshold > 0) {
                            throw BizException.validation("优惠金额(" + amount + ")不应大于门槛金额(" + threshold + ")，这不是优惠而是负价");
                        }
                    }
                }
                case PRICE -> {
                    double multiply = spec.getDouble("multiply", 1.0);
                    if (multiply < 0) {
                        throw BizException.validation("价格系数不能为负: " + multiply);
                    }
                    if (multiply > 5) {
                        throw BizException.validation("价格系数 " + multiply + " 超出合理范围(>5倍)，请确认是否写错");
                    }
                }
                case PROBABILITY -> {
                    double multiplier = spec.getDouble("multiplier", 1.0);
                    if (multiplier < 0) {
                        throw BizException.validation("概率系数不能为负: " + multiplier);
                    }
                }
            }
        }
    }

    public RuleSet load(Long datasetId, int version) {
        List<RulePO> rows = ruleMapper.selectList(new QueryWrapper<RulePO>()
                .eq("dataset_id", datasetId).eq("version", version).orderByAsc("id"));
        if (rows.isEmpty()) {
            throw BizException.notFound("规则集 v" + version + "(datasetId=" + datasetId + ")");
        }
        List<RuleSpec> specs = rows.stream()
                .map(r -> new RuleSpec(RuleType.valueOf(r.getRuleType()), json.readMap(r.getParams()), r.getDescription()))
                .toList();
        return new RuleSet(version, rows.get(0).getSetName(), specs);
    }

    /** 当前生效的基线规则集版本（数据集上记录的那个）。 */
    public int currentVersion(Long datasetId, Integer fallback) {
        if (fallback != null) {
            return fallback;
        }
        List<RulePO> rows = ruleMapper.selectList(new QueryWrapper<RulePO>()
                .eq("dataset_id", datasetId).orderByDesc("version").last("limit 1"));
        if (rows.isEmpty()) {
            throw BizException.notFound("数据集 " + datasetId + " 没有任何规则集");
        }
        return rows.get(0).getVersion();
    }

    public List<Map<String, Object>> listVersions(Long datasetId) {
        List<RulePO> rows = ruleMapper.selectList(new QueryWrapper<RulePO>()
                .eq("dataset_id", datasetId).orderByDesc("version"));
        Map<Integer, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (RulePO r : rows) {
            Map<String, Object> g = grouped.computeIfAbsent(r.getVersion(), v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", v);
                m.put("setName", r.getSetName());
                m.put("rules", new ArrayList<Map<String, Object>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) g.get("rules");
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("type", r.getRuleType());
            one.put("params", json.readMap(r.getParams()));
            one.put("description", r.getDescription());
            list.add(one);
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 下一个版本号。
     *
     * <p>【实测踩到的坑】不要用 {@code selectList(wrapper.select("max(version) as version"))}
     * —— MyBatis-Plus 在这种「只查聚合列」的查询上会返回一个元素为 {@code null} 的 List，
     * 于是 {@code rows.get(0).getVersion()} 直接 NPE（日志里表现为
     * 「List.get(int) 的返回值为 null」这种很费解的报错）。
     * 聚合查询直接用 JdbcTemplate 拿一个标量，语义最清楚也不会被 ORM 的映射规则干扰。
     */
    private int nextVersion(Long datasetId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM rule WHERE dataset_id = ?", Integer.class, datasetId);
        return (max == null ? 0 : max) + 1;
    }
}
