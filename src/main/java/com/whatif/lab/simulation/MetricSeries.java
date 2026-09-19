package com.whatif.lab.simulation;

import com.whatif.lab.domain.metric.MetricStats;
import com.whatif.lab.domain.metric.MetricType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个实验臂（Baseline 或 Scenario）在 N 次仿真上的指标序列。
 *
 * <p>存的是逐个仿真的原始样本，而不是累加统计量 —— 只有这样才可能事后算分位数、
 * 配对差值和显著性。数组按仿真序号对齐（第 i 个元素就是第 i 次仿真的结果），
 * 于是「Baseline[i] 与 Scenario[i] 用了同一个随机种子」这个配对关系天然保留下来。
 */
public final class MetricSeries {

    private final MetricType[] metrics = MetricType.values();
    private final Map<MetricType, double[]> samples = new EnumMap<>(MetricType.class);
    private final int simulations;

    public MetricSeries(int simulations) {
        this.simulations = simulations;
        for (MetricType m : metrics) {
            samples.put(m, new double[simulations]);
        }
    }

    public void put(int simulationIndex, ArmMetrics metrics) {
        for (MetricType m : this.metrics) {
            samples.get(m)[simulationIndex] = metrics.valueOf(m);
        }
    }

    public double[] samples(MetricType type) {
        return samples.get(type);
    }

    public int simulations() {
        return simulations;
    }

    public Map<MetricType, MetricStats> summarize() {
        Map<MetricType, MetricStats> out = new EnumMap<>(MetricType.class);
        for (MetricType m : metrics) {
            out.put(m, MetricStats.of(samples.get(m)));
        }
        return out;
    }

    /** 转成可直接落库/回传的 Map（键 = 指标名）。 */
    public Map<String, MetricStats> summarizeByName() {
        Map<String, MetricStats> out = new LinkedHashMap<>();
        summarize().forEach((k, v) -> out.put(k.name(), v));
        return out;
    }
}
