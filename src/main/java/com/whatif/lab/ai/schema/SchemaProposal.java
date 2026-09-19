package com.whatif.lab.ai.schema;

import com.whatif.lab.data.extract.DomainSpec;

import java.util.List;
import java.util.Map;

/**
 * Schema 提案 —— AI/启发式判列的**唯一输出物**，也是"能不能执行"的判据载体。
 *
 * <p><b>关键设计（比设计稿又收窄了一格）</b>：LLM 只输出"每列是什么角色"（语义判断），
 * <b>不输出任何可执行配置</b>。领域配置由确定性代码从角色推导（{@link RoleDeriver}）。
 * 这样 LLM 能弄坏的面只有"角色判错"这一种，而它会被确定性校验器与黄金集挡住；
 * 如果让 LLM 直接写配置，它能弄坏的面是"整个执行语义"，且错法无穷。
 *
 * <p>三个状态对应三种工况（设计稿 3.2）：
 * <pre>
 *   CAPABLE      决策型：表内直接有杠杆(LEVER)且有结果(OUTCOME) → 可直接做反事实
 *   IMPACT_ONLY  冲击型：没有真实杠杆，但可注入冲击（如"需求 +10%"）→ 需要声明注入方式
 *   NOT_CAPABLE  不可做：无杠杆且无法注入合理冲击 → **明确拒绝**并说明缺什么
 * </pre>
 */
public record SchemaProposal(String proposalId,
                             String schemaVersion,
                             String declaredDomain,
                             String templateHint,
                             Capability capability,
                             String capabilityReason,
                             List<RoleAssignment> columns,
                             /** 事件类型列里哪些取值算"成交"（语义判断，交给 LLM/人；没有事件类型列时为空）。 */
                             List<String> positiveEventValues,
                             /** 事件类型列里哪些取值算"取消/退货"。 */
                             List<String> negativeEventValues,
                             List<Unknown> unknowns,
                             List<String> warnings,
                             DomainSpec derivedSpec,
                             Provenance provenance) {

    /** 能跑哪种工况。 */
    public enum Capability { CAPABLE, IMPACT_ONLY, NOT_CAPABLE }

    /** 未决项 —— 一等公民：非空则**不允许自动冻结**，必须人来答。 */
    public record Unknown(String column, String question, String why) {
    }

    /** 溯源：算不出"当时凭什么、用的哪份画像、哪个模型"，就不叫可复现。 */
    public record Provenance(String producer,
                             String model,
                             String promptVersion,
                             String sourceProfileId,
                             String sourceProfileHash,
                             String createdAt) {
    }

    public boolean executable(double confidenceThreshold) {
        return capability != Capability.NOT_CAPABLE && unknowns.isEmpty()
                && columns.stream().noneMatch(c -> c.lowConfidence(confidenceThreshold));
    }

    /** 按角色分组，便于渲染与人读。 */
    public Map<ColumnRole, List<RoleAssignment>> byRole() {
        Map<ColumnRole, List<RoleAssignment>> m = new java.util.LinkedHashMap<>();
        for (RoleAssignment a : columns) {
            m.computeIfAbsent(a.role(), k -> new java.util.ArrayList<>()).add(a);
        }
        return m;
    }

    public List<RoleAssignment> of(ColumnRole role) {
        return columns.stream().filter(c -> c.role() == role).toList();
    }
}
