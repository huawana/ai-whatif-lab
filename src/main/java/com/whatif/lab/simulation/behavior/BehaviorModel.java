package com.whatif.lab.simulation.behavior;

import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.persistence.po.BehaviorModelPO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为模型本体（逻辑回归）。
 *
 * <p><b>为什么第一版选逻辑回归，而不是 Transformer / XGBoost</b>：
 * <ol>
 *   <li>可解释：每个客户、每个商品为什么被预测成这个概率，权重直接讲得清楚；</li>
 *   <li>可复现：训练是确定性的（全批量梯度下降 + 固定迭代数 + 固定标准化参数），
 *       同样的数据 + 同样的种子必然得到同样的权重，实验才能真正复现；</li>
 *   <li>快：7 维特征 × 几十万样本的训练是秒级，仿真要跑上千次也扛得住；</li>
 *   <li>反事实推断需要的正是「系数」——只要我们相信价格系数在观测区间内稳定，
 *       就能把「价格改成 X」代进模型算概率。深度模型给不出这种可检验的假设。</li>
 * </ol>
 * 这条「不上大模型做数值预测」的取舍，是面试时最能体现判断力的一点。
 */
public final class BehaviorModel {

    private final int version;
    private final List<String> featureNames;
    private final double[] coefficients;
    private final double intercept;
    private final double[] means;
    private final double[] stds;

    public BehaviorModel(int version, List<String> featureNames, double[] coefficients,
                         double intercept, double[] means, double[] stds) {
        if (coefficients.length != featureNames.size() || means.length != featureNames.size()
                || stds.length != featureNames.size()) {
            throw new IllegalArgumentException("特征数不一致：模型元数据的顺序必须与系数一一对应");
        }
        this.version = version;
        this.featureNames = List.copyOf(featureNames);
        this.coefficients = coefficients.clone();
        this.intercept = intercept;
        this.means = means.clone();
        this.stds = stds.clone();
    }

    public int version() {
        return version;
    }

    public List<String> featureNames() {
        return featureNames;
    }

    /** 预测购买概率。入参为**原始**特征（标准化在这里做，调用方不需要知道）。 */
    public double predict(double[] rawFeatures) {
        double z = intercept;
        for (int i = 0; i < coefficients.length; i++) {
            double std = stds[i] == 0 ? 1.0 : stds[i];
            z += coefficients[i] * ((rawFeatures[i] - means[i]) / std);
        }
        return sigmoid(z);
    }

    public static double sigmoid(double z) {
        // 分支写法避免 exp 溢出（z 很大/很小时 exp 会变 inf 或 0，直接算会出 NaN）
        if (z >= 0) {
            double e = Math.exp(-z);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(z);
        return e / (1.0 + e);
    }

    /** 权重（标准化尺度下可比）—— 用于解释「哪个特征最重要」。 */
    public Map<String, Double> weights() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < featureNames.size(); i++) {
            m.put(featureNames.get(i), coefficients[i]);
        }
        return m;
    }

    /** 按 |权重| 排序的贡献度（归一化成百分比，供 AI 解释与前端展示）。 */
    public List<Map<String, Object>> weightRanking() {
        double total = 0;
        for (double c : coefficients) {
            total += Math.abs(c);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < featureNames.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("feature", featureNames.get(i));
            row.put("coefficient", round(coefficients[i], 6));
            row.put("importance", total == 0 ? 0 : round(Math.abs(coefficients[i]) / total, 4));
            list.add(row);
        }
        list.sort((a, b) -> Double.compare((double) b.get("importance"), (double) a.get("importance")));
        return list;
    }

    public double intercept() {
        return intercept;
    }

    /**
     * 暴露标准化参数 {@code [means, stds]}。
     *
     * <p>仿真引擎的热循环要把 z 值拆成「可预计算的部分」以省掉每决策 7 维向量运算，
     * 拆解必须用到与 {@link #predict} 完全相同的 μ/σ。这里把参数暴露出来，
     * 而不是让引擎自己去读数据库 —— 保证那里用的就是模型内部同一份数组，
     * 不可能出现「引擎用了一套 μ/σ、模型用另一套」的静默错位。
     */
    public double[][] standardization() {
        return new double[][]{means.clone(), stds.clone()};
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    /** 从数据库行还原模型。 */
    public static BehaviorModel fromPO(BehaviorModelPO po, JsonCodec json) {
        List<String> names = json.readStringList(po.getFeatureNames());
        List<Double> coef = json.readDoubleList(po.getCoefficients());
        List<Double> means = json.readDoubleList(po.getFeatureMeans());
        List<Double> stds = json.readDoubleList(po.getFeatureStds());
        // 只查"模型自洽"（名字与系数一一对应）。
        // 【为什么不再和代码里的常量比维度】特征集现在是配置驱动的，代码里没有固定维度了 ——
        // "这个模型的系数该用哪套特征来解释"由 FeatureSetRegistry 按特征名精确匹配来判，
        // 匹配不上一律报错要求重训。见 SimulationEngine 里 byFeatureNames 的说明。
        if (coef.size() != names.size()) {
            throw new IllegalStateException("模型 v" + po.getVersion() + " 落库数据不自洽："
                    + "特征名 " + names.size() + " 个、系数 " + coef.size() + " 个。");
        }
        return new BehaviorModel(po.getVersion(), names, toArray(coef), po.getIntercept().doubleValue(),
                toArray(means), toArray(stds));
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
