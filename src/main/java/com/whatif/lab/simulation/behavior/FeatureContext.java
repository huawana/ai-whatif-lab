package com.whatif.lab.simulation.behavior;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 特征计算的输入包 —— <b>画像上下文</b>：从历史数据算出来的、与"做不做这次决策"无关的一切。
 *
 * <p><b>为什么把两边的字段都塞进一个 record</b>：特征按"作用域"分成三类 ——
 * 只看客户侧的（消费额、频次、最近活跃）、只看商品侧的（热门度、均价）、以及同时看两边的（客户×商品交互）。
 * 引擎要按作用域**分别预计算**（客户侧一次算好、商品侧一次算好，每个决策只算成对项），
 * 所以三类输入必须在同一个不可变包里传递，且必须能"只填一侧"（另一侧填 0）。
 *
 * <p><b>为什么这不是"零售专用"</b>：这些字段全部来自实体/事件两张泛化表 ——
 * "谁在何时对什么发生了什么、数量多少、金额多少"。任何交易型领域都有这些量，
 * 只是名字不同（客户/玩家、商品/道具、消费额/充值额）。
 */
public record FeatureContext(double customerEvents,
                             double customerQuantity,
                             double customerAmount,
                             double customerAvgAmount,
                             double customerRecencyDays,
                             double customerTenureDays,
                             double productEvents,
                             double productQuantity,
                             double productAvgPrice,
                             double productTypicalQuantity,
                             double pairCount) {

    /** 只填客户侧（商品侧置 0）：用于按客户预计算。 */
    public static FeatureContext customerOnly(double events, double quantity, double amount,
                                             double avgAmount, LocalDateTime firstSeen,
                                             LocalDateTime lastSeen, LocalDateTime referenceTime) {
        return new FeatureContext(events, quantity, amount, avgAmount,
                recencyDays(lastSeen, referenceTime), tenureDays(firstSeen, referenceTime),
                0, 0, 0, 0, 0);
    }

    /** 只填商品侧（客户侧置 0）：用于按商品预计算。 */
    public static FeatureContext productOnly(double events, double quantity, double avgPrice,
                                            double typicalQuantity) {
        return new FeatureContext(0, 0, 0, 0, 0, 0, events, quantity, avgPrice, typicalQuantity, 0);
    }

    /** 成对上下文 = 客户侧 + 商品侧 + 该客户与该商品的历史交互次数。 */
    public static FeatureContext pair(FeatureContext customer, FeatureContext product, double pairCount) {
        return new FeatureContext(customer.customerEvents(), customer.customerQuantity(),
                customer.customerAmount(), customer.customerAvgAmount(),
                customer.customerRecencyDays(), customer.customerTenureDays(),
                product.productEvents(), product.productQuantity(),
                product.productAvgPrice(), product.productTypicalQuantity(), pairCount);
    }

    /**
     * 最近一次活跃距今的天数。
     *
     * <p>数据里从没出现过的实体（lastSeen = null）取 60 天 —— 这是一个"明显不活跃"的兜底值，
     * 而不是 0：0 天意味着"刚刚活跃"，会把完全没有历史的对象当成最活跃的客户。
     */
    private static double recencyDays(LocalDateTime lastSeen, LocalDateTime referenceTime) {
        if (lastSeen == null || referenceTime == null) {
            return 60.0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(lastSeen, referenceTime));
    }

    private static double tenureDays(LocalDateTime firstSeen, LocalDateTime referenceTime) {
        if (firstSeen == null || referenceTime == null) {
            return 0.0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(firstSeen, referenceTime));
    }
}
