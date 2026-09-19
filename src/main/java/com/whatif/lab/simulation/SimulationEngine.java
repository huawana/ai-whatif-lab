package com.whatif.lab.simulation;

import com.whatif.lab.domain.metric.MetricStats;
import com.whatif.lab.domain.metric.MetricType;
import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.simulation.behavior.BehaviorModel;
import com.whatif.lab.simulation.behavior.FeatureContext;
import com.whatif.lab.simulation.behavior.FeatureSet;
import com.whatif.lab.simulation.behavior.FeatureSetRegistry;
import com.whatif.lab.simulation.rule.RuleEngine;
import com.whatif.lab.simulation.snapshot.CustomerProfile;
import com.whatif.lab.simulation.snapshot.DatasetSnapshot;
import com.whatif.lab.simulation.snapshot.ProductProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * 蒙特卡洛仿真引擎 —— 整个平台的核心。
 *
 * <p><b>它在算什么</b>：把「历史行为模型 + 一套业务规则」代进一个虚拟的一段时间，
 * 反复模拟「每个客户每次逛店看 K 个商品、按模型概率决定买不买、按规则结算」，
 * 得到 GMV/订单/转化/优惠成本/利润的<b>分布</b>，而不是一个数。
 * 同一段模拟换一套规则再跑一遍，两次结果的差异就是「这个 What-If 假设的答案」。
 *
 * <p><b>四个必须讲清楚的设计点</b>：
 * <ol>
 *   <li><b>公共随机数（Common Random Numbers）</b>：两臂用<b>完全相同的候选商品与判定随机数</b>，
 *       差别只来自规则引起的概率变化。这让两臂的差异里不含抽样噪声，
 *       配对差值的方差比独立模拟小一个量级 —— 几百次仿真就能得到显著结论。
 *       副作用（正是我们要的）：<b>规则不变时两臂结果逐位相同</b>，
 *       这就是本项目最强的一条自检（恒等性测试）。</li>
 *   <li><b>流级随机种子拆分</b>：每个 (仿真号, 客户, 会话) 用独立派生的种子
 *       （splitmix64 混合），而不是共用一条全局随机流。
 *       因为「买了以后要抽数量」会消耗随机数，而两臂买的东西不同 →
 *       共用全局流会让两臂的随机流错位，配对关系当场失效。拆流之后，
 *       每个会话内部的随机数消耗互不影响，且与线程/执行顺序无关。</li>
 *   <li><b>热循环用「拆解后的 z 值」预计算，并同时保留朴素实现做交叉校验</b>：
 *       每个决策只要 3 次乘法 + 1 次 sigmoid 而不是 7 维向量运算。
 *       这种优化最容易悄悄改变数值，所以引擎会抽样用朴素实现复算并报告最大偏差
 *       （诊断字段 {@code fastVsNaiveMaxDiff}），偏差非 0 就说明优化写错了。</li>
 *   <li><b>外推检测</b>：Scenario 的价格可能落到商品历史上从未出现过的区间。
 *       逻辑回归在观测区间外没有统计保证，所以引擎统计「决策落在历史价格区间外的比例」
 *       并在结果里显式给出 —— 而不是让用户以为外推结果同样可靠。</li>
 * </ol>
 */
@Component
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final RuleEngine ruleEngine;
    private final FeatureSetRegistry featureSets;

    public SimulationEngine(RuleEngine ruleEngine, FeatureSetRegistry featureSets) {
        this.ruleEngine = ruleEngine;
        this.featureSets = featureSets;
    }

    // ------------------------------------------------------------------ 配置与结果

    public record SimulationConfig(int durationDays,
                                  int simulations,
                                  long seed,
                                  int activeCustomers,
                                  int candidatesPerCustomer,
                                  int sessionIntervalDays,
                                  double grossMargin,
                                  int parallelism) {

        /** 实验周期内的购物会话数：30 天 / 7 天一次 ≈ 4 次。 */
        public int sessionsPerCustomer() {
            return Math.max(1, (int) Math.round((double) durationDays / Math.max(1, sessionIntervalDays)));
        }
    }

    /** 进度回调（SSE 推送用）。 */
    public interface ProgressListener {
        void onProgress(int percent, String stage);

        ProgressListener NOOP = (p, s) -> {
        };
    }

    public record Outcome(Map<MetricType, MetricStats> baseline,
                          Map<MetricType, MetricStats> scenario,
                          List<MetricComparison> comparisons,
                          MetricSeries baselineSeries,
                          MetricSeries scenarioSeries,
                          Map<String, Object> diagnostics) {
    }

    // ------------------------------------------------------------------ 主流程

    public Outcome run(DatasetSnapshot snapshot,
                       BehaviorModel model,
                       RuleSet baselineRules,
                       RuleSet scenarioRules,
                       SimulationConfig cfg,
                       ProgressListener listener) {

        long start = System.currentTimeMillis();
        listener.onProgress(5, "加载数据集快照");

        List<Integer> activeCustomers = pickActiveCustomers(snapshot, cfg);
        if (activeCustomers.isEmpty()) {
            throw new IllegalStateException("数据集中没有可用客户（历史购买为空）");
        }
        int productCount = snapshot.products().size();
        if (productCount == 0) {
            throw new IllegalStateException("数据集中没有商品实体");
        }

        int sessions = cfg.sessionsPerCustomer();
        listener.onProgress(15, "预计算模型系数分解");

        // ---- 价格与折扣的预计算（每个商品/客户只算一次，不在热循环里做规则解释） ----
        double[] listPrice = new double[productCount];
        double[] effectivePriceBaseline = new double[productCount];
        double[] effectivePriceScenario = new double[productCount];
        for (ProductProfile p : snapshot.products()) {
            listPrice[p.index()] = p.avgUnitPrice();
            effectivePriceBaseline[p.index()] = ruleEngine.effectiveUnitPrice(baselineRules, p.avgUnitPrice());
            effectivePriceScenario[p.index()] = ruleEngine.effectiveUnitPrice(scenarioRules, p.avgUnitPrice());
            // 【曾经的坑】这里原本提前算好 log(有效单价) 存进数组，热循环直接取用 ——
            // 变换一旦内联在引擎里，"引擎用了 log 但训练侧没同步"就会静默分叉（GMV=0 那类 bug）。
            // 现在价格特征也走特征集（作用域 PRICE），取值只有 FeatureSet 一个来源。
        }

        double[] perceivedDiscountBaseline = new double[snapshot.customers().size()];
        double[] perceivedDiscountScenario = new double[snapshot.customers().size()];
        for (CustomerProfile c : snapshot.customers()) {
            double basket = snapshot.customerAvgBasket(c.index());
            perceivedDiscountBaseline[c.index()] = ruleEngine.perceivedDiscountRate(baselineRules, basket);
            perceivedDiscountScenario[c.index()] = ruleEngine.perceivedDiscountRate(scenarioRules, basket);
        }
        double probMultiplierBaseline = ruleEngine.probabilityMultiplier(baselineRules);
        double probMultiplierScenario = ruleEngine.probabilityMultiplier(scenarioRules);

        // ---- 特征集：从"模型落库的特征名"反查特征集 ----
        // 必须精确匹配（名字与顺序都一致）：模型系数是按它自己训练时的特征顺序对齐的，
        // 用不同的特征集来解释它 = 系数静默错位 = 概率毫无意义却不报错。所以对不上就报错。
        FeatureSet fs = featureSets.byFeatureNames(model.featureNames());
        int[] productScope = fs.indicesOf(FeatureSet.Scope.PRODUCT);
        int[] priceScope = fs.indicesOf(FeatureSet.Scope.PRICE);
        int[] customerScope = fs.indicesOf(FeatureSet.Scope.CUSTOMER);
        int[] discountScope = fs.indicesOf(FeatureSet.Scope.DISCOUNT);
        int[] pairScope = fs.indicesOf(FeatureSet.Scope.PAIR);
        log.info("仿真使用特征集 {}：维度 {}（商品侧 {} / 价格 {} / 客户侧 {} / 折扣 {} / 成对 {}）",
                fs.name(), fs.dimension(), productScope.length, priceScope.length,
                customerScope.length, discountScope.length, pairScope.length);

        // ---- 把 z = intercept + Σ w·(x−μ)/σ 拆成与决策无关的部分 ----
        // 【为什么按"作用域"分桶，而不是像以前那样写死 7 个特征下标】
        // 写死下标等于把"特征必须是这 7 个"钉死在引擎里 —— 换领域就得改引擎。
        // 现在改成按配置声明的作用域分桶：客户侧、商品侧各算一次；价格与折扣按臂算；
        // 成对项留给热循环。**换特征集，这段代码一行不用改。**
        // 但那个踩过的坑必须继续防住：预计算与朴素路径只能调用 FeatureSet 的同一个取值方法。
        ModelLens lens = new ModelLens(model, fs);
        double[] prodPartBaseline = new double[productCount];
        double[] prodPartScenario = new double[productCount];
        for (int p = 0; p < productCount; p++) {
            FeatureContext cx = snapshot.contextForProduct(p);
            double base = 0;
            double scen = 0;
            for (int i : productScope) {
                base += lens.term(i, fs.value(i, cx, listPrice[p], 0));
                scen += lens.term(i, fs.value(i, cx, listPrice[p], 0));
            }
            for (int i : priceScope) {
                base += lens.term(i, fs.value(i, cx, effectivePriceBaseline[p], 0));
                scen += lens.term(i, fs.value(i, cx, effectivePriceScenario[p], 0));
            }
            prodPartBaseline[p] = base;
            prodPartScenario[p] = scen;
        }
        double[] custPartBaseline = new double[snapshot.customers().size()];
        double[] custPartScenario = new double[snapshot.customers().size()];
        for (CustomerProfile c : snapshot.customers()) {
            FeatureContext cx = snapshot.contextForCustomer(c.index());
            double base = 0;
            double scen = 0;
            for (int i : customerScope) {
                base += lens.term(i, fs.value(i, cx, 0, 0));
                scen += lens.term(i, fs.value(i, cx, 0, 0));
            }
            for (int i : discountScope) {
                base += lens.term(i, fs.value(i, cx, 0, perceivedDiscountBaseline[c.index()]));
                scen += lens.term(i, fs.value(i, cx, 0, perceivedDiscountScenario[c.index()]));
            }
            custPartBaseline[c.index()] = base;
            custPartScenario[c.index()] = scen;
        }

        listener.onProgress(25, "开始蒙特卡洛仿真");

        MetricSeries baselineSeries = new MetricSeries(cfg.simulations());
        MetricSeries scenarioSeries = new MetricSeries(cfg.simulations());
        long[] outOfSupportBaseline = {0};
        long[] outOfSupportScenario = {0};
        long[] decisions = {0};
        // 用朴素实现复算的抽样校验（证明 z 值拆解与逐维预测完全等价）
        double[] maxDiff = {0};
        int[] naiveChecked = {0};

        IntStream stream = IntStream.range(0, cfg.simulations());
        if (cfg.parallelism() > 0) {
            stream = stream.parallel();
        }
        int[] progress = {0};
        stream.forEach(sim -> {
            ArmMetrics[] arms = simulateOne(snapshot, activeCustomers, sessions, cfg, sim,
                    listPrice, effectivePriceBaseline, effectivePriceScenario,
                    prodPartBaseline, prodPartScenario,
                    custPartBaseline, custPartScenario, lens, fs, pairScope,
                    probMultiplierBaseline, probMultiplierScenario,
                    baselineRules, scenarioRules,
                    outOfSupportBaseline, outOfSupportScenario, decisions,
                    maxDiff, naiveChecked, model, perceivedDiscountBaseline, perceivedDiscountScenario);
            baselineSeries.put(sim, arms[0]);
            scenarioSeries.put(sim, arms[1]);
            int done = ++progress[0];
            if (cfg.parallelism() == 0 && (done % Math.max(1, cfg.simulations() / 10) == 0)) {
                listener.onProgress(25 + (int) (60.0 * done / cfg.simulations()),
                        "仿真进行中 " + done + "/" + cfg.simulations());
            }
        });

        listener.onProgress(90, "汇总指标与显著性检验");
        Map<MetricType, MetricStats> baselineSummary = baselineSeries.summarize();
        Map<MetricType, MetricStats> scenarioSummary = scenarioSeries.summarize();
        List<MetricComparison> comparisons = new ArrayList<>();
        for (MetricType m : MetricType.values()) {
            comparisons.add(MetricComparison.of(m, baselineSummary.get(m), scenarioSummary.get(m),
                    baselineSeries.samples(m), scenarioSeries.samples(m)));
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("activeCustomers", activeCustomers.size());
        diagnostics.put("totalCustomers", snapshot.customers().size());
        diagnostics.put("products", productCount);
        diagnostics.put("sessionsPerCustomer", sessions);
        diagnostics.put("decisionsPerSimulation", decisions[0] / Math.max(1, cfg.simulations()));
        diagnostics.put("totalDecisions", decisions[0]);
        long perArm = Math.max(1, decisions[0] / 2);
        diagnostics.put("outOfSupportRatioBaseline", round((double) outOfSupportBaseline[0] / perArm, 6));
        diagnostics.put("outOfSupportRatioScenario", round((double) outOfSupportScenario[0] / perArm, 6));
        diagnostics.put("outOfSupportNote", "决策中有效价格落在该商品历史价格区间之外的比例；"
                + ">0 表示结果含外推成分，逻辑回归在观测区间外无统计保证。");
        diagnostics.put("fastVsNaiveMaxDiff", maxDiff[0]);
        diagnostics.put("naiveChecks", naiveChecked[0]);
        diagnostics.put("fastVsNaiveNote", "热循环的 z 值拆解优化 vs 逐维朴素预测的最大绝对偏差；"
                + "应≈1e-15（浮点误差），非 0 说明优化破坏了数值一致性。");
        diagnostics.put("historyReferenceTime", String.valueOf(snapshot.referenceTime()));
        diagnostics.put("customerAvgBasketMean", round(meanBasket(snapshot, activeCustomers), 4));
        diagnostics.put("perceivedDiscountBaselineMean", round(mean(perceivedDiscountBaseline, activeCustomers), 6));
        diagnostics.put("perceivedDiscountScenarioMean", round(mean(perceivedDiscountScenario, activeCustomers), 6));
        diagnostics.put("probabilityMultiplierBaseline", probMultiplierBaseline);
        diagnostics.put("probabilityMultiplierScenario", probMultiplierScenario);
        diagnostics.put("meanListPrice", round(mean(listPrice), 4));
        diagnostics.put("meanEffectivePriceBaseline", round(mean(effectivePriceBaseline), 4));
        diagnostics.put("meanEffectivePriceScenario", round(mean(effectivePriceScenario), 4));

        // 口径校准校验：真实历史同窗口 GMV 与基线仿真 GMV 之比
        double historical = snapshot.historicalGmv(activeCustomers);
        double simulated = baselineSummary.get(MetricType.GMV).mean();
        diagnostics.put("historicalGmvSameWindow", round(historical, 2));
        diagnostics.put("historicalWindowDays", snapshot.recentWindowDays());
        diagnostics.put("gmvScaleFactor", simulated == 0 ? null : round(historical / simulated, 4));
        diagnostics.put("gmvScaleFactorNote", "真实历史同窗口 GMV ÷ 基线仿真 GMV。仿真是「每客户每会话抽样 K 个候选商品」"
                + "的口径，绝对量级与真实业务不可直接比较；该系数把偏差显式量化出来，"
                + "判断策略优劣请以两臂的相对变化为准。");
        diagnostics.put("elapsedMs", System.currentTimeMillis() - start);

        listener.onProgress(100, "完成");
        log.info("仿真完成 sims={} 客户={} 决策={} 耗时={}ms 基线GMV={} 场景GMV={} 外推比例={} 优化偏差={}",
                cfg.simulations(), activeCustomers.size(), decisions[0], diagnostics.get("elapsedMs"),
                baselineSummary.get(MetricType.GMV).mean(), scenarioSummary.get(MetricType.GMV).mean(),
                diagnostics.get("outOfSupportRatioScenario"), maxDiff[0]);

        return new Outcome(baselineSummary, scenarioSummary, comparisons, baselineSeries, scenarioSeries, diagnostics);
    }

    // ------------------------------------------------------------------ 单次仿真

    private ArmMetrics[] simulateOne(DatasetSnapshot snapshot,
                                     List<Integer> activeCustomers,
                                     int sessions,
                                     SimulationConfig cfg,
                                     int simIndex,
                                     double[] listPrice,
                                     double[] priceBaseline,
                                     double[] priceScenario,
                                     double[] prodPartBaseline,
                                     double[] prodPartScenario,
                                     double[] custPartBaseline,
                                     double[] custPartScenario,
                                     ModelLens lens,
                                     FeatureSet fs,
                                     int[] pairScope,
                                     double probMultiplierBaseline,
                                     double probMultiplierScenario,
                                     RuleSet baselineRules,
                                     RuleSet scenarioRules,
                                     long[] outOfSupportBaseline,
                                     long[] outOfSupportScenario,
                                     long[] decisions,
                                     double[] maxDiff,
                                     int[] naiveChecked,
                                     BehaviorModel model,
                                     double[] perceivedDiscountBaseline,
                                     double[] perceivedDiscountScenario) {

        int K = cfg.candidatesPerCustomer();
        int productCount = snapshot.products().size();
        RuleEngine engine = ruleEngine;

        ArmMetrics base = ArmMetrics.ZERO;
        ArmMetrics scen = ArmMetrics.ZERO;
        long localOutBaseline = 0;
        long localOutScenario = 0;
        long localDecisions = 0;
        double localMaxDiff = 0;
        int localChecked = 0;

        int[] prods = new int[K];
        double[] uniforms = new double[K];
        int[] qtys = new int[K];

        for (int d : activeCustomers) {
            for (int k = 0; k < sessions; k++) {
                // 会话级随机流：两臂共用「候选商品 + 判定随机数」，
                // 因此两臂看到的选项与掷骰子完全相同，差异只来自概率本身。
                Random sessionRng = new Random(mix(cfg.seed(), simIndex, d, k));
                for (int i = 0; i < K; i++) {
                    prods[i] = sessionRng.nextInt(productCount);
                    uniforms[i] = sessionRng.nextDouble();
                }
                /*
                 * 数量抽样：两臂各持一条**同种子**的随机流。
                 *
                 * 【实测踩到的坑，代价是"恒等实验两臂居然不相等"】
                 * 我最初给两臂用了两个不同种子的流（seed+7919 vs seed+104729），
                 * 想法是"互不干扰"。但配对实验的要点在于：第 i 次抽样在两臂里必须是**同一个数**。
                 * 两条不同种子的流 → 两臂买到同样的商品、却买到不同的件数，
                 * 于是表现成一个极其迷惑的现象：
                 *     订单量/转化率两臂完全一致（它们不依赖件数），
                 *     而 GMV / 利润 / 客单价 / 优惠成本全都不同（它们依赖件数）。
                 * 正确做法是「同种子 + 各自独立消费」：
                 * 谁消耗到第几个随机数互不影响，但同序号的抽样值必然相同。
                 */
                long qtySeed = mix(cfg.seed() + 7919L, simIndex, d, k);
                Random qtyRngBase = new Random(qtySeed);
                Random qtyRngScen = new Random(qtySeed);

                for (int arm = 0; arm < 2; arm++) {
                    double[] prices = arm == 0 ? priceBaseline : priceScenario;
                    double[] prodPart = arm == 0 ? prodPartBaseline : prodPartScenario;
                    double[] custPart = arm == 0 ? custPartBaseline : custPartScenario;
                    double probMult = arm == 0 ? probMultiplierBaseline : probMultiplierScenario;
                    RuleSet armRules = arm == 0 ? baselineRules : scenarioRules;
                    Random qtyRng = arm == 0 ? qtyRngBase : qtyRngScen;
                    long armOutOfSupport = 0;

                    List<RuleEngine.BasketLine> basket = null;
                    for (int i = 0; i < K; i++) {
                        int p = prods[i];
                        ProductProfile product = snapshot.products().get(p);
                        double listP = listPrice[p];

                        // 外推检测：Scenario 的价格可能落到该商品历史从未出现过的区间。
                        // 逻辑回归在观测区间之外没有统计保证，所以这个比例必须显式暴露出来。
                        if (prices[p] < product.minPrice() || prices[p] > product.maxPrice()) {
                            armOutOfSupport++;
                        }

                        double z = lens.intercept() + custPart[d] + prodPart[p];
                        /*
                         * 【实测踩到的坑，被 fastVsNaive 交叉校验抓出（偏差 0.0987）】
                         * 这里原本有个 `if (pairCount > 0)` 的"优化"：没成交过的客户×商品对就跳过该项。
                         * 但这一项在朴素实现里始终存在，且 x = log1p(0) = 0 会被标准化成
                         * (0 − μ_pair)/σ_pair ≠ 0 —— 于是所有"从未买过"的组合都少算了一个固定偏移，
                         * 而这个常数项恰恰是模型学出来的"零亲和力的基准水平"。
                         * 后果：概率被系统性压低/抬高，GMV 量级不对，而一切看起来都"正常"。
                         * 教训：**标准化之后，"特征值=0"不等于"这一项=0"**。
                         * 因为 log1p(0)=0 本身只花一次加法，这个 if 连性能优化都算不上 —— 纯粹的错。
                         */
                        // 成对作用域特征（有几个由配置决定）：只有它们必须每个决策现算
                        double armDiscount = arm == 0 ? perceivedDiscountBaseline[d] : perceivedDiscountScenario[d];
                        if (pairScope.length > 0) {
                            FeatureContext pairCtx = snapshot.contextFor(d, p);
                            for (int fi : pairScope) {
                                z += lens.term(fi, fs.value(fi, pairCtx, prices[p], armDiscount));
                            }
                        }
                        double pBuy = BehaviorModel.sigmoid(z) * probMult;
                        if (pBuy > 1) {
                            pBuy = 1;
                        }
                        localDecisions++;

                        // 抽样校验：用朴素实现（完整特征向量 + model.predict）复算，比对 z 拆解的正确性
                        if (((localDecisions + simIndex) % 977 == 0) && localChecked < 200) {
                            double[] raw = fs.build(snapshot.contextFor(d, p), prices[p], armDiscount);
                            double naive = Math.min(1, model.predict(raw) * probMult);
                            double diff = Math.abs(naive - pBuy);
                            if (diff > localMaxDiff) {
                                localMaxDiff = diff;
                            }
                            localChecked++;
                        }

                        if (uniforms[i] < pBuy) {
                            int qty = sampleQuantity(product, qtyRng);
                            if (basket == null) {
                                basket = new ArrayList<>(4);
                            }
                            mergeLine(basket, p, qty, listP);
                        }
                    }

                    // 一次「有成交的购物会话」= 一张订单；没有成交也计入会话数（转化率的分母）
                    ArmMetrics result;
                    if (basket == null || basket.isEmpty()) {
                        result = new ArmMetrics(0, 0, 0, 1, 0, 0);
                    } else {
                        RuleEngine.PricedBasket priced = engine.price(armRules, basket, cfg.grossMargin());
                        result = new ArmMetrics(priced.netAmount(), priced.grossAmount(), 1, 1,
                                priced.discountAmount(), priced.cogs());
                    }
                    if (arm == 0) {
                        base = base.plus(result);
                        localOutBaseline += armOutOfSupport;
                    } else {
                        scen = scen.plus(result);
                        localOutScenario += armOutOfSupport;
                    }
                }
            }
        }

        // 并行执行时多个仿真会同时回写统计量，这里只需保证计数不丢（顺序无关紧要）
        synchronized (decisions) {
            outOfSupportBaseline[0] += localOutBaseline;
            outOfSupportScenario[0] += localOutScenario;
            decisions[0] += localDecisions;
            if (localMaxDiff > maxDiff[0]) {
                maxDiff[0] = localMaxDiff;
            }
            naiveChecked[0] += localChecked;
        }
        return new ArmMetrics[]{base, scen};
    }

    private static void mergeLine(List<RuleEngine.BasketLine> basket, int productIndex, int qty, double listPrice) {
        for (int i = 0; i < basket.size(); i++) {
            RuleEngine.BasketLine l = basket.get(i);
            if (l.productIndex() == productIndex) {
                basket.set(i, new RuleEngine.BasketLine(productIndex, l.quantity() + qty, listPrice));
                return;
            }
        }
        basket.add(new RuleEngine.BasketLine(productIndex, qty, listPrice));
    }

    /** 数量抽样：以该商品历史平均购买数量为中心，±50% 抖动，至少 1 件。 */
    private static int sampleQuantity(ProductProfile product, Random rng) {
        int typical = Math.max(1, product.typicalQuantity());
        double jitter = 0.5 + rng.nextDouble();
        return Math.max(1, (int) Math.round(typical * jitter));
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 选择活跃客户。
     *
     * <p>用固定种子做确定性洗牌后取前 N 个 —— 不能直接取「前 N 个」，
     * 因为快照索引是按首次出现（时间顺序）分配的，直接取前 N 会系统性偏向早期客户，
     * 而早期客户的行为可能已经过时（活跃度特征会因为 recency 大而普遍偏低），
     * 样本就有偏了。
     */
    private List<Integer> pickActiveCustomers(DatasetSnapshot snapshot, SimulationConfig cfg) {
        List<Integer> eligible = new ArrayList<>();
        for (CustomerProfile c : snapshot.customers()) {
            if (c.purchaseLines() > 0) {
                eligible.add(c.index());
            }
        }
        Random rng = new Random(cfg.seed());
        for (int i = eligible.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = eligible.get(i);
            eligible.set(i, eligible.get(j));
            eligible.set(j, tmp);
        }
        int n = Math.min(cfg.activeCustomers(), eligible.size());
        List<Integer> selected = new ArrayList<>(eligible.subList(0, n));
        selected.sort(Integer::compareTo);   // 排序保证遍历顺序确定（并行/串行结果一致）
        return selected;
    }

    /** splitmix64：把 (种子, 仿真号, 客户, 会话) 混合成一个稳定的 64 位种子。 */
    static long mix(long seed, long a, long b, long c) {
        long z = seed + 0x9E3779B97F4A7C15L * (a + 1) + 0xBF58476D1CE4E5B9L * (b + 1) + 0x94D049BB133111EBL * (c + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double meanBasket(DatasetSnapshot snapshot, List<Integer> customers) {
        double sum = 0;
        for (int c : customers) {
            sum += snapshot.customerAvgBasket(c);
        }
        return customers.isEmpty() ? 0 : sum / customers.size();
    }

    private static double mean(double[] values, List<Integer> indexes) {
        double sum = 0;
        for (int i : indexes) {
            sum += values[i];
        }
        return indexes.isEmpty() ? 0 : sum / indexes.size();
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return values.length == 0 ? 0 : sum / values.length;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }

    /**
     * 模型系数「镜片」：把标准化项 w·(x−μ)/σ 拆成可预计算的部分。
     *
     * <p>z = intercept + Σ wᵢ(xᵢ−μᵢ)/σᵢ = intercept + Σ [wᵢ/σᵢ · xᵢ] − Σ [wᵢμᵢ/σᵢ]。
     * 与决策无关的项（按特征集声明的 CUSTOMER / PRODUCT 作用域）先算好；
     * 与决策有关的是「价格」「感知折扣」与成对项，其中前两项在同一个臂内对同一客户/商品也是常量。
     * <b>具体有哪几项、分别属于哪个作用域，全由特征集配置决定</b> ——
     * 引擎不假设特征的个数与名字，只按作用域分桶。
     */
    private static final class ModelLens {
        private final BehaviorModel model;
        private final double[] weights;
        private final double[] means;
        private final double[] stds;
        private final double intercept;

        ModelLens(BehaviorModel model, FeatureSet featureSet) {
            this.model = model;
            int dim = featureSet.dimension();
            this.weights = new double[dim];
            this.means = new double[dim];
            this.stds = new double[dim];
            for (int i = 0; i < dim; i++) {
                // 按特征集的顺序取系数。特征名与特征集已由注册表精确校验过，
                // 这里只是把它变成防御性断言：缺了就是 bug，不能静默当 0。
                Double w = model.weights().get(featureSet.names().get(i));
                if (w == null) {
                    throw new IllegalStateException("模型缺少特征 " + featureSet.names().get(i)
                            + " 的系数：特征名与特征集 " + featureSet.name() + " 不一致");
                }
                this.weights[i] = w;
            }
            // BehaviorModel 内部持有标准化参数，这里通过一次「已知输入」反推不可行，
            // 因此直接读它的原始数组（见 BehaviorModel#standardization()）
            double[][] std = model.standardization();
            System.arraycopy(std[0], 0, means, 0, dim);
            System.arraycopy(std[1], 0, stds, 0, dim);
            this.intercept = model.intercept();
        }

        double intercept() {
            return intercept;
        }

        /** 单特征项 w·(x−μ)/σ。 */
        double term(int index, double rawFeature) {
            double s = stds[index] == 0 ? 1.0 : stds[index];
            return weights[index] * (rawFeature - means[index]) / s;
        }
    }
}
