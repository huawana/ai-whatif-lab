package com.whatif.lab.simulation.snapshot;

import com.whatif.lab.simulation.behavior.FeatureContext;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 数据集内存快照：仿真与训练都只依赖它，不再碰数据库。
 *
 * <p><b>为什么要做快照</b>：一次实验要跑 N(=200~2000) 次蒙特卡洛，
 * 每次都去查库是不可接受的（同样的是读 50 万行事件）。
 * 快照把「历史」一次性压成三份紧凑结构：客户画像、商品画像、客户×商品的历史购买次数，
 * 之后所有计算都在堆内完成 —— 这也是「实验可在几秒内跑完」的前提。
 *
 * <p><b>时间切分（防止数据泄漏）</b>：训练必须用「过去预测未来」，
 * 所以把时间轴按事件的 70% 分位切开：
 * <pre>
 *   |-------- 历史窗口（画像） --------|--- 标签窗口（正样本） ---|
 *                          referenceTime      dataEnd
 * </pre>
 * 画像只用历史窗口的数据算，标签窗口里的购买作为训练正样本 ——
 * 若用全量数据算画像，「客户最后买过这个商品」这条信息本身就泄露了标签，
 * AUC 会虚高到 0.95+，而仿真里根本拿不到这种信息。这是本项目最容易悄悄做错的一点。
 */
public final class DatasetSnapshot {

    private final Long datasetId;
    private final LocalDateTime referenceTime;
    private final List<CustomerProfile> customers;
    private final List<ProductProfile> products;
    private final Map<Long, Integer> historyPairCounts;
    private final List<PurchaseLabel> labelingPurchases;
    private final Map<String, Integer> customerIndex;
    private final Map<String, Integer> productIndex;
    /** 参考时点前 {@link #recentWindowDays} 天内，每个客户在历史里的真实 GMV（用于口径校准校验）。 */
    private final Map<Integer, Double> recentGmvByCustomer;
    private final int recentWindowDays;
    private final LocalDateTime dataStart;
    private final LocalDateTime dataEnd;

    /** 标签窗口内的购买（训练正样本的原料）。 */
    public record PurchaseLabel(int customerIndex, int productIndex, double unitPrice, int quantity,
                                LocalDateTime time) {
    }

    public DatasetSnapshot(Long datasetId,
                           LocalDateTime referenceTime,
                           LocalDateTime dataStart,
                           LocalDateTime dataEnd,
                           List<CustomerProfile> customers,
                           List<ProductProfile> products,
                           Map<Long, Integer> historyPairCounts,
                           List<PurchaseLabel> labelingPurchases,
                           Map<String, Integer> customerIndex,
                           Map<String, Integer> productIndex,
                           Map<Integer, Double> recentGmvByCustomer,
                           int recentWindowDays) {
        this.datasetId = datasetId;
        this.referenceTime = referenceTime;
        this.dataStart = dataStart;
        this.dataEnd = dataEnd;
        this.customers = List.copyOf(customers);
        this.products = List.copyOf(products);
        this.historyPairCounts = Map.copyOf(historyPairCounts);
        this.labelingPurchases = List.copyOf(labelingPurchases);
        this.customerIndex = Map.copyOf(customerIndex);
        this.productIndex = Map.copyOf(productIndex);
        this.recentGmvByCustomer = Map.copyOf(recentGmvByCustomer);
        this.recentWindowDays = recentWindowDays;
    }

    public Long datasetId() {
        return datasetId;
    }

    public LocalDateTime referenceTime() {
        return referenceTime;
    }

    public LocalDateTime dataStart() {
        return dataStart;
    }

    public LocalDateTime dataEnd() {
        return dataEnd;
    }

    public List<CustomerProfile> customers() {
        return customers;
    }

    public List<ProductProfile> products() {
        return products;
    }

    public List<PurchaseLabel> labelingPurchases() {
        return labelingPurchases;
    }

    public int historyPairCount(int customerIdx, int productIdx) {
        return historyPairCounts.getOrDefault(pairKey(customerIdx, productIdx), 0);
    }

    public static long pairKey(int customerIdx, int productIdx) {
        return ((long) customerIdx << 20) | productIdx;
    }

    /** 全部「历史窗口内成交过」的客户×商品对。负采样需要用它把已观测交互排除掉。 */
    public java.util.Set<Long> historyPairKeys() {
        return historyPairCounts.keySet();
    }

    /** 客户的历史客单价（用于计算「感知折扣率」）。没有任何历史时给一个保守的默认值。 */
    public double customerAvgBasket(int customerIdx) {
        CustomerProfile c = customers.get(customerIdx);
        return c.avgLineAmount() > 0 ? c.avgLineAmount() : 20.0;
    }

    /**
     * 构造特征上下文 —— 训练与仿真共用这一个方法，保证口径绝对一致。
     *
     * <p>两个刻意的默认值（都是「不知道」的保守表达，而不是编造信息）：
     * <ul>
     *   <li>新客户（历史无购买）→ 活跃度取「60 天前」而不是 0 天：
     *       把「没有历史」当成「刚刚活跃过」会让模型高估新客；</li>
     *   <li>新商品（历史无销量）→ 热门度为 0，模型若认为热门度为正相关就会给它低概率，
     *       这是合理惩罚（冷启动商品确实更难卖）。</li>
     * </ul>
     */
    public FeatureContext contextFor(int customerIdx, int productIdx) {
        return FeatureContext.pair(customerContext(customerIdx), productContext(productIdx),
                historyPairCount(customerIdx, productIdx));
    }

    /**
     * 只填客户侧的上下文 —— 给引擎按客户预计算"客户侧特征"用。
     *
     * <p>为什么可以只填一侧：特征词汇表里 {@code entity*} 类型只读客户侧字段，
     * 这条不变量由 {@code FeatureSetSpec.TYPE_SCOPE} 声明、由 {@code FeatureSetTest} 守着。
     * 商品侧留下 0，不会被读到。
     */
    public FeatureContext contextForCustomer(int customerIdx) {
        return customerContext(customerIdx);
    }

    /** 只填商品侧的上下文 —— 给引擎按商品预计算"商品侧特征"用。 */
    public FeatureContext contextForProduct(int productIdx) {
        return productContext(productIdx);
    }

    private FeatureContext customerContext(int customerIdx) {
        CustomerProfile c = customers.get(customerIdx);
        return FeatureContext.customerOnly(c.purchaseLines(), c.totalQuantity(), c.totalSpend(),
                c.avgLineAmount(), c.firstSeen(), c.lastSeen(), referenceTime);
    }

    private FeatureContext productContext(int productIdx) {
        ProductProfile p = products.get(productIdx);
        return FeatureContext.productOnly(p.purchaseLines(), p.totalQuantity(), p.avgUnitPrice(),
                p.typicalQuantity());
    }

    /**
     * 历史窗口里，这批客户在「参考时点前 recentWindowDays 天」内产生的真实 GMV。
     *
     * <p>它的用处只有一个，但很关键：<b>口径校准校验</b>。
     * 仿真是按「每客户每会话抽样 K 个候选商品」的口径算的，
     * 这个口径下的绝对 GMV 与真实业务量级不可比（差一个采样倍率）。
     * 把「真实历史同窗口 GMV」和「基线仿真 GMV」放在一起展示，
     * 用户一眼就能看出绝对值偏差多少倍，而不是被一个看着像真的数字误导。
     */
    public double historicalGmv(List<Integer> customerIndexes) {
        double sum = 0;
        for (int idx : customerIndexes) {
            sum += recentGmvByCustomer.getOrDefault(idx, 0.0);
        }
        return sum;
    }

    public int recentWindowDays() {
        return recentWindowDays;
    }
}
