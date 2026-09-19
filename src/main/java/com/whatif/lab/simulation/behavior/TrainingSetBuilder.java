package com.whatif.lab.simulation.behavior;

import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.simulation.rule.RuleEngine;
import com.whatif.lab.simulation.snapshot.CustomerProfile;
import com.whatif.lab.simulation.snapshot.DatasetSnapshot;
import com.whatif.lab.simulation.snapshot.ProductProfile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 训练集构造 —— 逻辑回归「学什么」就定义在这里。
 *
 * <p><b>为什么没有负样本、必须自己造</b>：
 * 交易数据里只有「买过」的记录，没有「看过但没买」的记录。这是隐式反馈（implicit feedback）
 * 的经典问题。标准做法是<b>负采样</b>：把「客户 × 商品」中历史同期没有成交的那些组合
 * 当作负样本。这里 1 个正样本配 N 个负样本（N 可配，默认 4）。
 *
 * <p>这个选择带来一个必须向使用者讲清楚的后果：
 * 模型输出的概率是「在负采样分布下的购买倾向」，<b>不是真实世界的转化率</b>
 * （真实转化率是百分之几，采样口径下会高得多）。
 * 所以平台所有结论都以 <b>Baseline 与 Scenario 的相对变化</b>为准，
 * 而这个相对变化对采样倍率是稳健的 —— 因为两臂用的是同一套采样。
 * 这条口径声明同时出现在 API 响应与 AI 解释的提示词里。
 *
 * <p><b>训练/推理口径一致性</b>：负样本的商品是「在商品全集上均匀随机」抽的，
 * 仿真时的候选商品也用同样的方式抽（见 SimulationEngine）。两处口径必须一致，
 * 否则模型会把「采样分布的差异」误当成「规则带来的变化」。
 *
 * <p><b>无数据泄漏</b>：正样本取自标签窗口（时间轴后 30%），特征全部取自历史窗口
 * 且不含本次成交的信息（价格用成交价是允许的 —— 那是决策时已知的条件，不是结果）。
 */
@Component
public class TrainingSetBuilder {

    private final RuleEngine ruleEngine;

    public TrainingSetBuilder(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public record TrainingSet(double[][] features, int[] labels, Map<String, Object> stats) {
    }

    public TrainingSet build(DatasetSnapshot snapshot, FeatureSet featureSet, RuleSet baselineRules,
                             int negativeRatio, long seed) {
        // ---- 正样本：标签窗口内真实发生的「客户 × 商品」成交（按对去重） ----
        Set<Long> positivePairs = new LinkedHashSet<>();
        for (DatasetSnapshot.PurchaseLabel label : snapshot.labelingPurchases()) {
            positivePairs.add(DatasetSnapshot.pairKey(label.customerIndex(), label.productIndex()));
        }
        if (positivePairs.isEmpty()) {
            throw new IllegalStateException("标签窗口内没有任何购买事件，无法训练模型（数据集时间跨度过短？）");
        }

        int positives = positivePairs.size();
        int negatives = (int) ((long) positives * Math.max(1, negativeRatio));
        double[][] x = new double[positives + negatives][];
        int[] y = new int[positives + negatives];

        int row = 0;
        for (DatasetSnapshot.PurchaseLabel label : snapshot.labelingPurchases()) {
            long key = DatasetSnapshot.pairKey(label.customerIndex(), label.productIndex());
            if (!positivePairs.remove(key)) {
                continue;   // 同一对只取第一次成交（避免同一对重复计入正样本）
            }
            x[row] = featuresFor(snapshot, featureSet, baselineRules,
                    label.customerIndex(), label.productIndex(), label.unitPrice());
            y[row] = 1;
            row++;
        }

        // ---- 负样本：均匀抽样「客户 × 商品」，剔除已观测到的交互 ----
        Random rng = new Random(seed);
        List<CustomerProfile> customers = snapshot.customers();
        List<ProductProfile> products = snapshot.products();
        // 已观测交互集合：这些「客户×商品」历史成交过，不能当负样本（隐式反馈的标准做法）。
        // 直接取快照里的键集合，而不是双重循环逐个探测 —— 后者是 O(客户×商品) 的。
        Set<Long> historyPairs = snapshot.historyPairKeys();
        List<Integer> eligibleCustomers = customers.stream()
                .filter(c -> c.purchaseLines() > 0).map(CustomerProfile::index).toList();

        int created = 0;
        int attempts = 0;
        int maxAttempts = negatives * 20;
        while (created < negatives && attempts < maxAttempts) {
            attempts++;
            int ci = eligibleCustomers.get(rng.nextInt(eligibleCustomers.size()));
            int pi = rng.nextInt(products.size());
            ProductProfile product = products.get(pi);
            if (product.avgUnitPrice() <= 0) {
                continue;   // 没有历史价格的商品无法参与价格特征
            }
            long key = DatasetSnapshot.pairKey(ci, pi);
            if (historyPairs.contains(key)) {
                continue;   // 历史买过 → 不当负样本（隐式反馈的标准做法）
            }
            x[row] = featuresFor(snapshot, featureSet, baselineRules, ci, pi, product.avgUnitPrice());
            y[row] = 0;
            row++;
            created++;
        }

        double[][] trimmedX = new double[row][];
        int[] trimmedY = new int[row];
        System.arraycopy(x, 0, trimmedX, 0, row);
        System.arraycopy(y, 0, trimmedY, 0, row);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("positives", positives);
        stats.put("negatives", created);
        stats.put("negativeRatioActual", positives == 0 ? 0 : round((double) created / positives, 4));
        stats.put("negativeSampleAttempts", attempts);
        stats.put("eligibleCustomers", eligibleCustomers.size());
        stats.put("products", products.size());
        stats.put("historyPairsExcluded", historyPairs.size());
        stats.put("historyPairsExcludedNote", "历史窗口内成交过的客户×商品对，已从负样本候选中剔除。");
        stats.put("ruleVersionUsedForDiscountFeature", baselineRules.version());
        stats.put("featureSet", featureSet.name());
        stats.put("featureNames", featureSet.names());
        stats.put("featureScopes", featureSet.scopeBreakdown());
        stats.put("note", "负样本为均匀抽样构造（隐式反馈负采样口径），因此预测概率是倾向分数，"
                + "不是真实转化率；平台结论以 Baseline/Scenario 相对变化为准。");
        return new TrainingSet(trimmedX, trimmedY, stats);
    }

    /**
     * 单样本特征。
     *
     * <p>注意「感知折扣率」用的是 <b>Baseline 规则</b>：历史数据是在当时的规则下产生的，
     * 模型要学的是「客户在那个规则环境下对价格的反应」。
     * 之后把新规则的感知折扣率代进去算概率，才是反事实推断。
     * 若这里用「新规则」算折扣，等于用未来的信息训练历史数据，模型会学到错的关系。
     */
    private double[] featuresFor(DatasetSnapshot snapshot, FeatureSet featureSet, RuleSet baselineRules,
                                 int customerIdx, int productIdx, double unitPrice) {
        double effectivePrice = ruleEngine.effectiveUnitPrice(baselineRules, unitPrice);
        double discount = ruleEngine.perceivedDiscountRate(baselineRules, snapshot.customerAvgBasket(customerIdx));
        // 特征口径只有一个来源：FeatureSet（配置驱动的特征集）。
        // 这里与仿真引擎的快路径调用的是同一个方法，物理上不可能分叉。
        return featureSet.build(snapshot.contextFor(customerIdx, productIdx), effectivePrice, discount);
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
