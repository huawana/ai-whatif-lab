package com.whatif.lab.domain.scenario;

import com.whatif.lab.common.BizException;

import java.util.List;
import java.util.Map;

/**
 * Scenario DSL —— AI 与仿真引擎之间的<b>唯一</b>接口。
 *
 * <p><b>为什么必须有这一层（本项目最重要的设计决策之一）</b>：
 * <pre>
 *   错误做法：用户 → LLM → 直接执行 Java 代码 / 直接给出「订单量+10%」
 *   本项目的做法：用户 → LLM → Scenario DSL → Schema 校验 → 业务校验 → 仿真引擎
 * </pre>
 * LLM 是「编译器前端」，只负责把自然语言翻译成结构化意图；
 * 仿真引擎是「执行器」，负责算出所有会被追责的数字。
 * 中间这层 DSL 带来三个可验证的工程收益：
 * <ol>
 *   <li><b>可校验</b>：DSL 结构固定，能对着规则表逐字段验证（阈值不能为负、折扣不能超过原价…），
 *       模型的幻觉在进入引擎之前就被拦住；</li>
 *   <li><b>可复现</b>：DSL 归一化后算 SHA-256 作为缓存键与实验快照，同样的 DSL 必然算出同样的结果；</li>
 *   <li><b>可替换</b>：没有 AI（或 AI 关掉）时，DSL 可以手工 POST 进来，整条计算链路一字不改。</li>
 * </ol>
 *
 * <p>DSL 形状（对应文档第十二节）：
 * <pre>
 * {
 *   "datasetId": 1,
 *   "name": "满80减15",
 *   "durationDays": 30,
 *   "changes": [
 *     {"ruleType": "DISCOUNT", "field": "threshold", "from": 100, "to": 80},
 *     {"ruleType": "DISCOUNT", "field": "amount",    "from": 10,  "to": 15}
 *   ]
 * }
 * </pre>
 * {@code changes} 是「相对当前规则集的差分」，不是完整规则集 ——
 * 因为用户在自然语言里说的就是「把 A 改成 B」，用差分表达：
 * ① 与用户意图一比一对应，AI 的翻译错误肉眼可见；
 * ② 未提及的规则自动继承 Baseline，不会因为 LLM 漏输出一条规则而悄悄改变实验条件。
 */
public record ScenarioDsl(Long datasetId,
                          String name,
                          Integer durationDays,
                          List<Change> changes,
                          Map<String, Object> extras) {

    /** 单条改动：对第 {@code index} 条（或某类型）规则的某个字段做 from → to。 */
    public record Change(String ruleType,
                         String field,
                         Object from,
                         Object to,
                         String note) {
    }

    public ScenarioDsl {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** 校验失败时抛业务异常（HTTP 200 + code=1002），不是 500。 */
    public void validateBasics() {
        if (datasetId == null) {
            throw BizException.validation("DSL 缺少 datasetId");
        }
        for (Change c : changes) {
            if (c.ruleType() == null || c.field() == null) {
                throw BizException.validation("change 缺少 ruleType 或 field");
            }
            if (c.to() == null) {
                throw BizException.validation("change 的 to 不能为空（from 可以为空表示新增字段）");
            }
        }
    }

    /**
     * 归一化：用于计算稳定的 hash。
     *
     * <p>为什么要归一化：{@code changes} 的顺序、{@code name} 的空格、
     * {@code extras} 的键序都不应影响「这是不是同一个实验」。
     * 不归一化的话，同一句话经 LLM 两次生成可能得到两个 hash，
     * SingleFlight 与结果缓存就全部失效（这是很隐蔽的缓存穿透）。
     */
    public String canonicalForm() {
        StringBuilder sb = new StringBuilder();
        sb.append("ds=").append(datasetId).append(";dur=").append(durationDays == null ? 30 : durationDays).append(";");
        changes.stream()
                .map(c -> (c.ruleType() + "." + c.field() + "=" + c.to()).toUpperCase())
                .sorted()
                .forEach(s -> sb.append(s).append(";"));
        return sb.toString();
    }
}
