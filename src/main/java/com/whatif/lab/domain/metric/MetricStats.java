package com.whatif.lab.domain.metric;

/**
 * 单指标的分布统计（跨 N 次蒙特卡洛仿真的分布）。
 *
 * <p>为什么必须给分布而不给一个数：单次仿真的结果受随机性影响，
 * 只报均值是「伪精确」。P5/P95 才能回答老板真正关心的问题 ——
 * 「这个方案最差情况有多差」。均值相同、方差不同的两个方案，
 * 决策含义完全不同。
 *
 * <p>分位数口径：线性插值（等价于 numpy.percentile 默认行为），
 * <pre>idx = q × (n − 1)，取值 = v[⌊idx⌋] + (v[⌈idx⌉] − v[⌊idx⌋]) × (idx − ⌊idx⌋)</pre>
 * 口径必须写死，否则不同实现（我这边 Java / 报告那边 Python）对同一个样本会得出不同的 P95，
 * 而「两个不同实现算出同一个数」正是本项目验证策略的核心。
 */
public record MetricStats(double mean, double p5, double p50, double p95, double std, int samples) {

    public static MetricStats of(double[] values) {
        if (values == null || values.length == 0) {
            return new MetricStats(0, 0, 0, 0, 0, 0);
        }
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        double sum = 0;
        for (double v : sorted) {
            sum += v;
        }
        double mean = sum / n;
        double sq = 0;
        for (double v : sorted) {
            double d = v - mean;
            sq += d * d;
        }
        // 样本方差（n-1）：仿真是抽样，用总体方差会低估不确定性
        double std = n > 1 ? Math.sqrt(sq / (n - 1)) : 0;
        return new MetricStats(mean, percentile(sorted, 0.05), percentile(sorted, 0.50),
                percentile(sorted, 0.95), std, n);
    }

    /** 线性插值分位数，入参必须已排序。 */
    public static double percentile(double[] sortedAsc, double q) {
        int n = sortedAsc.length;
        if (n == 0) {
            return 0;
        }
        if (n == 1) {
            return sortedAsc[0];
        }
        double idx = q * (n - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) {
            return sortedAsc[lo];
        }
        double frac = idx - lo;
        return sortedAsc[lo] + (sortedAsc[hi] - sortedAsc[lo]) * frac;
    }
}
