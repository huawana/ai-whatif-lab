package com.whatif.lab.simulation;

import com.whatif.lab.common.BizException;
import com.whatif.lab.domain.metric.MetricStats;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.domain.rule.RuleSpec;
import com.whatif.lab.domain.rule.RuleType;
import com.whatif.lab.domain.scenario.ScenarioApplier;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.simulation.rule.RuleEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仿真核心的纯逻辑测试：规则引擎 + 指标统计 + 情景应用器。
 *
 * <p>这三块都是<b>纯函数</b>（无 IO、无状态），所以能在这里被穷举验证 ——
 * 而它们恰恰是最容易出"静默错"的地方：规则解释错了、分位数算错了、
 * 情景改动应用到了错误的字段上，都不会抛异常，只会让报告里的数字错掉。
 */
class SimulationCoreTest {

    private static final RuleSet BASELINE = new RuleSet(1, "基线", List.of(
            new RuleSpec(RuleType.DISCOUNT, Map.of("threshold", 100, "amount", 10), "满100减10"),
            new RuleSpec(RuleType.PRICE, Map.of("multiply", 1.0), "无价格调整"),
            new RuleSpec(RuleType.PROBABILITY, Map.of("multiplier", 1.0), "无概率干预")));

    private final RuleEngine engine = new RuleEngine();

    // ------------------------------------------------------------------ 规则引擎

    @Test
    @DisplayName("感知折扣率：门槛不下降 → 折扣率不上升（模型内的数学性质）")
    void perceivedDiscountIsMonotoneInThreshold() {
        RuleSet lower = new RuleSet(1, "门槛降到80", List.of(
                new RuleSpec(RuleType.DISCOUNT, Map.of("threshold", 80, "amount", 15), "")));
        RuleSet higher = new RuleSet(1, "门槛升到500", List.of(
                new RuleSpec(RuleType.DISCOUNT, Map.of("threshold", 500, "amount", 10), "")));

        double basket = 120.0;   // 典型客单价
        double atBaseline = engine.perceivedDiscountRate(BASELINE, basket);
        double atLower = engine.perceivedDiscountRate(lower, basket);
        double atHigher = engine.perceivedDiscountRate(higher, basket);

        assertTrue(atLower >= atBaseline, "门槛降到 80 且优惠加大 → 感知折扣率不该下降");
        assertTrue(atHigher <= atBaseline, "门槛升到 500 → 够不着门槛，感知折扣率为 0");
        assertEquals(0.0, atHigher, 1e-9, "客单价 120 够不到 500 的门槛");
        assertEquals(15.0 / 120, atLower, 1e-9, "满80减15 在 120 元客单价下等于 12.5% 折扣");
    }

    @Test
    @DisplayName("结算：优惠不能超过订单金额本身（防御性夹紧）")
    void discountCannotExceedBasket() {
        RuleSet crazy = new RuleSet(1, "荒谬规则", List.of(
                new RuleSpec(RuleType.DISCOUNT, Map.of("threshold", 10, "amount", 99999), "")));
        var priced = engine.price(crazy, List.of(new RuleEngine.BasketLine(0, 1, 20.0)), 0.35);
        assertTrue(priced.netAmount() >= 0, "净额不能为负");
        assertEquals(0.0, priced.netAmount(), 1e-9, "优惠被夹紧到订单金额，净额恰好 0");
    }

    @Test
    @DisplayName("成本按挂牌价算，不按折后价算（打折是让利，进货成本不会减少）")
    void cogsUsesListPriceNotDiscountedPrice() {
        double listPrice = 100.0;
        int qty = 2;
        double margin = 0.35;
        RuleSet half = new RuleSet(1, "半价", List.of(
                new RuleSpec(RuleType.PRICE, Map.of("multiply", 0.5), "")));
        var priced = engine.price(half, List.of(new RuleEngine.BasketLine(0, qty, listPrice)), margin);

        // 毛收入按折后价：100 × 0.5 × 2 = 100
        assertEquals(100.0, priced.grossAmount(), 1e-9);
        // 成本按**挂牌价**：100 × 2 × (1−0.35) = 130
        // 这一条是刻意的陷阱式断言：如果谁把成本也按折后价算（=65），
        // 就会得出"打五折利润反而更高"的荒谬结论，这里会立刻红。
        assertEquals(130.0, priced.cogs(), 1e-9);
        assertEquals(-30.0, priced.profit(), 1e-9, "净额 100 − 成本 130 = 亏 30：五折确实是亏本买卖");
    }

    @Test
    @DisplayName("价格与概率规则：乘数连乘、下限保护")
    void priceAndProbabilityMultipliers() {
        RuleSet rs = new RuleSet(1, "组合", List.of(
                new RuleSpec(RuleType.PRICE, Map.of("multiply", 0.9), ""),
                new RuleSpec(RuleType.PRICE, Map.of("multiply", 1.1), ""),
                new RuleSpec(RuleType.PROBABILITY, Map.of("multiplier", 1.5), ""),
                new RuleSpec(RuleType.PROBABILITY, Map.of("multiplier", 2.0), "")));
        assertEquals(0.99, engine.priceMultiplier(rs), 1e-9, "0.9 × 1.1 = 0.99");
        assertEquals(3.0, engine.probabilityMultiplier(rs), 1e-9, "1.5 × 2.0 = 3.0");
        // 0 或负价必须被兜住，否则对数特征会爆炸
        RuleSet free = new RuleSet(1, "免费", List.of(
                new RuleSpec(RuleType.PRICE, Map.of("multiply", 0.0), "")));
        assertEquals(0.01, engine.effectiveUnitPrice(free, 50.0), 1e-9, "有效价格下限 0.01");
    }

    // ------------------------------------------------------------------ 情景应用器

    @Test
    @DisplayName("情景应用器：只改被提到的字段，其它字段原样继承")
    void scenarioApplierIsADiff() {
        RuleSet scenario = ScenarioApplier.apply(BASELINE, List.of(
                new ScenarioDsl.Change("DISCOUNT", "threshold", 100, 80, null),
                new ScenarioDsl.Change("DISCOUNT", "amount", 10, 15, null)));

        var discount = scenario.ofType(RuleType.DISCOUNT).get(0);
        assertEquals(80.0, discount.getDouble("threshold", -1), 1e-9);
        assertEquals(15.0, discount.getDouble("amount", -1), 1e-9);
        // 未提到的规则必须原样继承 —— 否则 LLM 漏输出一条规则就会悄悄改变实验条件
        var price = scenario.ofType(RuleType.PRICE).get(0);
        assertEquals(1.0, price.getDouble("multiply", -1), 1e-9);
        assertEquals(1.0, scenario.ofType(RuleType.PROBABILITY).get(0).getDouble("multiplier", -1), 1e-9);
    }

    @Test
    @DisplayName("情景应用器：非法字段必须被拒绝（而不是静默忽略）")
    void scenarioApplierRejectsUnknownField() {
        BizException e1 = assertThrows(BizException.class, () -> ScenarioApplier.apply(BASELINE,
                List.of(new ScenarioDsl.Change("DISCOUNT", "discountRate", 1, 2, null))));
        assertTrue(e1.getMessage().contains("没有字段"), "错误信息要能直接指出问题字段: " + e1.getMessage());

        BizException e2 = assertThrows(BizException.class, () -> ScenarioApplier.apply(BASELINE,
                List.of(new ScenarioDsl.Change("COUPON", "amount", 1, 2, null))));
        assertTrue(e2.getMessage().contains("未知规则类型"), "未知规则类型要明确拒绝");

        assertThrows(BizException.class, () -> ScenarioApplier.apply(BASELINE,
                List.of(new ScenarioDsl.Change("DISCOUNT", "amount", 1, "不是数字", null))));
    }

    @Test
    @DisplayName("恒等情景：空 changes → 规则集与基线逐字段相同")
    void identityScenarioChangesNothing() {
        RuleSet scenario = ScenarioApplier.apply(BASELINE, List.of());
        assertTrue(ScenarioApplier.isIdentity(List.of()));
        assertEquals(BASELINE.rules().size(), scenario.rules().size());
        for (int i = 0; i < BASELINE.rules().size(); i++) {
            assertEquals(BASELINE.rules().get(i).params(), scenario.rules().get(i).params(),
                    "第 " + i + " 条规则的参数必须逐字段相同");
        }
    }

    // ------------------------------------------------------------------ 指标统计

    @Test
    @DisplayName("分位数口径：线性插值，且与手算结果一致")
    void percentileIsLinearInterpolation() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        // 线性插值口径：idx = q×(n−1)
        assertEquals(1.0, MetricStats.percentile(v, 0.0), 1e-9);
        assertEquals(10.0, MetricStats.percentile(v, 1.0), 1e-9);
        assertEquals(5.5, MetricStats.percentile(v, 0.5), 1e-9);
        assertEquals(1.45, MetricStats.percentile(v, 0.05), 1e-9, "0.05×9=0.45 → 1 + 0.45×(2−1)");
        assertEquals(9.55, MetricStats.percentile(v, 0.95), 1e-9, "0.95×9=8.55 → 9 + 0.55×(10−9)");
    }

    @Test
    @DisplayName("样本标准差用 n−1（仿真是抽样，不是总体）")
    void stdUsesSampleFormula() {
        double[] v = {2, 4, 4, 4, 5, 5, 7, 9};   // 经典样本：均值 5
        MetricStats s = MetricStats.of(v);
        assertEquals(5.0, s.mean(), 1e-12);
        // 样本标准差 = 2.13809（总体标准差 = 2.0）—— 用错公式会让不确定性被低估
        assertEquals(2.13809, s.std(), 1e-5);
        assertEquals(8, s.samples());
    }

    @Test
    @DisplayName("全零样本不产生 NaN（恒等实验会走这条路）")
    void allZeroSamplesAreSafe() {
        MetricStats s = MetricStats.of(new double[]{0, 0, 0, 0});
        assertEquals(0.0, s.mean(), 1e-12);
        assertEquals(0.0, s.std(), 1e-12);
        assertEquals(0.0, s.p95(), 1e-12);
    }

    @Test
    @DisplayName("配对对比：差值全零 → p=1 且不显著、置信区间收缩到 0")
    void pairedComparisonOnIdenticalArms() {
        double[] a = {10, 20, 30, 40, 50};
        double[] b = a.clone();
        var cmp = MetricComparison.of(com.whatif.lab.domain.metric.MetricType.GMV,
                MetricStats.of(a), MetricStats.of(b), a, b);
        assertEquals(0.0, cmp.deltaMean(), 1e-12);
        assertEquals(1.0, cmp.pValue(), 1e-9, "没有差异 → p 必须为 1");
        assertFalse(cmp.significant(), "没有差异不能显著");
        assertEquals(0.0, cmp.ci95Low(), 1e-12);
        assertEquals(0.0, cmp.ci95High(), 1e-12);
    }

    @Test
    @DisplayName("配对对比：稳定差值 → 显著，且置信区间不含 0")
    void pairedComparisonDetectsRealDifference() {
        double[] a = new double[50];
        double[] b = new double[50];
        for (int i = 0; i < 50; i++) {
            a[i] = 1000 + i;          // 基线
            b[i] = 1100 + i;          // 情景：稳定 +100
        }
        var cmp = MetricComparison.of(com.whatif.lab.domain.metric.MetricType.GMV,
                MetricStats.of(a), MetricStats.of(b), a, b);
        assertEquals(100.0, cmp.deltaMean(), 1e-9);
        assertTrue(cmp.significant(), "稳定的 +100 差异必须显著");
        assertTrue(cmp.ci95Low() > 0, "置信区间下界应大于 0（方向明确）");
        assertTrue(cmp.pValue() < 0.001, "如此整齐的差异 p 值应极小");
        // 相对效应 = 差值均值 ÷ 基线均值 = 100 / 1024.5
        assertEquals(0.097608, cmp.relativeEffect(), 1e-5);
    }
}
