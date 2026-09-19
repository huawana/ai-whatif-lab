package com.whatif.lab.simulation;

import com.whatif.lab.common.Statistics;
import com.whatif.lab.domain.metric.MetricStats;
import com.whatif.lab.domain.metric.MetricType;

/**
 * 单指标的两臂对比 —— 一个实验里最核心的产出对象。
 *
 * <p><b>四个层次的输出，从粗到细</b>：
 * <ol>
 *   <li><b>分布</b>：两臂各自的 Mean / P5 / P50 / P95 / Std —— 回答"最差情况有多差"；</li>
 *   <li><b>配对差值</b>：Dᵢ = Scenarioᵢ − Baselineᵢ，用同一批随机种子，Dᵢ 里不含抽样噪声；</li>
 *   <li><b>显著性</b>：配对 t 检验的 t 值、<b>p 值</b>、差值 <b>95% 置信区间</b>；</li>
 *   <li><b>效应量</b>：相对效应（差值 ÷ 基线均值）与 Cohen's d（差值 ÷ 差值标准差）。</li>
 * </ol>
 *
 * <p><b>为什么"第 4 层"不能省</b>：p 值只回答"这个差异是不是随机的"，
 * 不回答"这个差异值不值得做"。仿真次数一大，0.3% 的差异都能做到 p &lt; 0.001 ——
 * 但 0.3% 的 GMV 增长不值得动一次线上规则。
 * <b>p 值决定"信不信"，效应量决定"做不做"，两者都要有，判断权交给人。</b>
 *
 * <p><b>配对实验为什么这么强</b>：两臂共用相同随机种子（公共随机数法），
 * 第 i 次仿真之间的差异只由规则变化引起，抽样噪声被抵消掉了。
 * 于是差值序列的方差远小于"两臂独立模拟"时的方差 —— 同样的仿真次数下，
 * 能分辨的效应要小一个量级；换个说法：要得到同样的统计功效，仿真次数可以少一个量级，
 * 一次实验从"跑几分钟"变成"跑几秒"。
 */
public record MetricComparison(MetricType metric,
                               MetricStats baseline,
                               MetricStats scenario,
                               double changeRate,
                               double deltaMean,
                               double deltaStd,
                               double deltaStdError,
                               double tStat,
                               int degreesOfFreedom,
                               double pValue,
                               double ci95Low,
                               double ci95High,
                               double relativeEffect,
                               double cohensD,
                               boolean significant,
                               String unit,
                               String label) {

    /** 显著性水平（双侧 5%）。写成常量而不是散落的 0.05，避免口径漂移。 */
    public static final double ALPHA = 0.05;

    public static MetricComparison of(MetricType type, MetricStats baseline, MetricStats scenario,
                                      double[] baselineSamples, double[] scenarioSamples) {
        int n = Math.min(baselineSamples.length, scenarioSamples.length);
        double[] delta = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            delta[i] = scenarioSamples[i] - baselineSamples[i];
            sum += delta[i];
        }
        double mean = n == 0 ? 0 : sum / n;
        double sq = 0;
        for (double d : delta) {
            sq += (d - mean) * (d - mean);
        }
        double std = n > 1 ? Math.sqrt(sq / (n - 1)) : 0;
        double se = (n > 0 && std > 0) ? std / Math.sqrt(n) : 0;
        int df = Math.max(n - 1, 1);

        /*
         * 【测试抓到的真 bug】se = 0 是 t 检验的**退化情形**，有两种完全相反的语义，
         * 必须分开处理，不能都当成"无差异"：
         *
         *   ① 差值全为 0（两臂逐位相同，恒等实验走这里）
         *        → 没有差异：t=0、p=1、不显著、置信区间收缩到 0
         *   ② 差值恒为某个非零值（例如每次仿真都稳定 +100）
         *        → 效应**完全确定**：数学上 t = 均值/0 = ∞，p → 0，应当**显著**
         *
         * 我最初把两种都写成了 t=0 → p=1，于是②被误判成"不显著" ——
         * 这会让「规则改变带来的确定性效果」被报告成"随机波动范围内"，方向完全错。
         * 用 Double.POSITIVE_INFINITY 会被 JSON 序列化拒绝（Infinity 不是合法 JSON），
         * 所以用一个有限大值当替身，并在注释里写清它的含义。
         */
        final double T_INFINITY_SURROGATE = 1e6;

        double t;
        double p;
        double ciLow;
        double ciHigh;
        if (se == 0) {
            if (mean == 0) {
                t = 0;
                p = 1.0;
                ciLow = 0;
                ciHigh = 0;
            } else {
                t = mean > 0 ? T_INFINITY_SURROGATE : -T_INFINITY_SURROGATE;
                p = 0.0;
                ciLow = mean;
                ciHigh = mean;
            }
        } else {
            t = mean / se;
            p = Statistics.twoSidedPValue(t, df);
            double tCrit = Statistics.tQuantile(1 - ALPHA, df);
            ciLow = mean - tCrit * se;
            ciHigh = mean + tCrit * se;
        }

        double change = baseline.mean() == 0 ? 0 : (scenario.mean() - baseline.mean()) / Math.abs(baseline.mean());
        double relativeEffect = baseline.mean() == 0 ? 0 : mean / Math.abs(baseline.mean());
        // Cohen's d 同样有退化情形：方差为 0 且均值非 0 时效应量是无穷大（完全确定的效果），
        // 不能用 0 表示 —— 否则 effectSizeLevel() 会把它报成"无效应"，与 p=0 自相矛盾。
        double cohensD = std == 0
                ? (mean == 0 ? 0 : (mean > 0 ? T_INFINITY_SURROGATE : -T_INFINITY_SURROGATE))
                : mean / std;

        return new MetricComparison(type, baseline, scenario,
                round(change, 6), round(mean, 6), round(std, 6), round(se, 6), round(t, 4), df,
                round(p, 8), round(ciLow, 6), round(ciHigh, 6), round(relativeEffect, 6),
                round(cohensD, 4),
                p < ALPHA && mean != 0,
                type.unit(), type.label());
    }

    /** 置信区间的可读形式（给报告与 AI 解释用）。 */
    public String confidenceIntervalText() {
        return String.format("[%s, %s]", trim(ci95Low), trim(ci95High));
    }

    /** 效应量分级（Cohen 经验阈值）。仅作参考，不替代业务判断。 */
    public String effectSizeLevel() {
        double d = Math.abs(cohensD);
        if (d == 0) {
            return "无";
        }
        if (d < 0.2) {
            return "很小";
        }
        if (d < 0.5) {
            return "小";
        }
        if (d < 0.8) {
            return "中";
        }
        return "大";
    }

    private static String trim(double v) {
        return String.valueOf(Math.round(v * 10000) / 10000.0);
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
