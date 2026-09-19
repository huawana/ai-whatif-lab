package com.whatif.lab.api.dto;

import com.whatif.lab.domain.metric.MetricStats;
import com.whatif.lab.simulation.MetricComparison;

import java.util.List;
import java.util.Map;

/**
 * 实验结果视图（对外返回，同时是缓存里存的对象）。
 *
 * <p>为什么要有一个统一的「视图」而不是把 PO 直接返回：
 * 一次实验的结果由三部分组成 —— 落库的元信息（实验参数、版本、种子）、
 * 两臂的分布统计、以及诊断信息（外推比例、口径校准、优化校验）。
 * 缓存里存的也是这个对象，所以「从缓存返回」与「现算返回」的响应结构<b>完全一致</b>，
 * 调用方（含 AI Agent）不需要区分两条路径。这是让缓存对上层透明的前提。
 */
public record ExperimentResultView(Long experimentId,
                                   String status,
                                   int progress,
                                   String stage,
                                   String datasetName,
                                   Long datasetId,
                                   String scenarioName,
                                   String naturalLanguage,
                                   String baselineRuleSet,
                                   String scenarioRuleSet,
                                   String behaviorModelInfo,
                                   long randomSeed,
                                   int simulationCount,
                                   int durationDays,
                                   List<MetricComparison> comparisons,
                                   Map<String, MetricStats> baselineMetrics,
                                   Map<String, MetricStats> scenarioMetrics,
                                   Map<String, Object> diagnostics,
                                   Map<String, Object> experimentMeta,
                                   boolean cacheHit,
                                   long elapsedMs,
                                   String errorMessage) {

    /**
     * 把结果里的「身份与运行期字段」替换成**本次请求**的真实值：experimentId / cacheHit / elapsedMs / status。
     *
     * <p><b>为什么连 experimentId 都要覆盖（实测踩到的坑）</b>：
     * 缓存里存的是"第一次算出这个结果"的那份视图 —— 它的 experimentId 是**当时那次实验的编号**。
     * 而 {@code create()} 每次请求都会新建一行 experiment（即便参数完全相同），
     * 于是会出现这种事：
     * <pre>
     *   请求 A → 新建实验 13 → 无缓存 → 真算 1967ms → 结果进缓存（视图里写着 experimentId=13）
     *   请求 B → 新建实验 14 → 命中缓存 → 秒回 55ms
     *            → 但返回的视图里 experimentId 仍然是 13 ❌
     * </pre>
     * 调用方拿到 14 这个响应，按 experimentId 去查却查到 13 那一行 ——
     * 对一个以"可审计"为卖点的平台，这是身份错误，不是小瑕疵。
     * 所以：<b>计算结果可共享（进缓存），身份与运行状态必须每次请求单独写。</b>
     *
     * <p>还有一个同源的坑：结果视图是在<b>计算过程中</b>就被构造出来的，那时 status 还是 RUNNING ——
     * 于是命中缓存的人会看到「实验还在跑」（明明秒回）。所以 status 也在这里统一改成 SUCCESS：
     * 这份视图描述的是"已算完的结果"，任务状态由 experiment 表与 SSE 进度负责。
     */
    public ExperimentResultView withRequestIdentity(Long experimentId, boolean cacheHit, long elapsedMs) {
        return new ExperimentResultView(experimentId, "SUCCESS", 100, stage, datasetName, datasetId,
                scenarioName, naturalLanguage, baselineRuleSet, scenarioRuleSet, behaviorModelInfo,
                randomSeed, simulationCount, durationDays, comparisons, baselineMetrics, scenarioMetrics,
                diagnostics, experimentMeta, cacheHit, elapsedMs, errorMessage);
    }

    /** 关键结论的一句话摘要（给 AI 解释与前端头图用；数字全部来自 comparisons，不是模型编的）。 */
    public String headline() {
        if (comparisons == null || comparisons.isEmpty()) {
            return "无指标结果";
        }
        StringBuilder sb = new StringBuilder();
        for (MetricComparison c : comparisons) {
            if (c.metric() == com.whatif.lab.domain.metric.MetricType.GMV
                    || c.metric() == com.whatif.lab.domain.metric.MetricType.ORDERS
                    || c.metric() == com.whatif.lab.domain.metric.MetricType.PROFIT
                    || c.metric() == com.whatif.lab.domain.metric.MetricType.DISCOUNT_COST) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(c.label()).append(' ')
                        .append(c.changeRate() >= 0 ? "+" : "")
                        .append(String.format("%.2f%%", c.changeRate() * 100))
                        .append(c.significant() ? "(显著)" : "(不显著)");
            }
        }
        return sb.toString();
    }
}
