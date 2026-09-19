package com.whatif.lab.simulation.rule;

import com.whatif.lab.domain.rule.RuleSet;
import com.whatif.lab.domain.rule.RuleSpec;
import com.whatif.lab.domain.rule.RuleType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则引擎：把「规则集」解释成对单个决策/单个购物篮的具体影响。
 *
 * <p><b>规则引擎分成两条通道，这一点是刻意设计的</b>：
 * <ol>
 *   <li><b>感知通道</b>（影响行为概率）：客户看到的「有效价格」与「感知折扣率」。
 *       规则变了 → 客户感知变了 → 购买概率变了。这是需求侧的传导。</li>
 *   <li><b>结算通道</b>（影响金额）：真正下单时的订单金额、优惠金额、成本。
 *       这是供给侧的算账。</li>
 * </ol>
 * 两条通道分开，是因为「客户感知的折扣」与「平台实际付出的优惠」本来就不是一回事
 * （梯度折扣、满减门槛导致的组合差异）。把它们混成一个数，就无法解释
 * 「为什么优惠成本涨了 45% 但订单只涨了 3%」这类真正的业务问题。
 *
 * <p>引擎本身对领域无感知：它只认「价格类规则」「优惠类规则」「概率类规则」，
 * 电商的满减、游戏的抽卡概率提升，在引擎眼里都是同一件事。
 */
@Component
public class RuleEngine {

    /** 购物篮中的一行（结算前）。 */
    public record BasketLine(int productIndex, int quantity, double listPrice) {
    }

    /** 结算后的一行。 */
    public record PricedLine(int productIndex, int quantity, double listPrice,
                             double effectiveUnitPrice, double amount) {
    }

    /**
     * 结算后的购物篮。
     *
     * @param cogs 成本 = Σ 数量 × 挂牌价 × (1 − 毛利率)。成本按**挂牌价**算，不因打折而降 ——
     *             打折是平台让利，进货成本不会因此减少，这一点经常被做错
     *             （把成本也按折后价算，会得出「打折不亏」的错误结论）。
     */
    public record PricedBasket(List<PricedLine> lines, double grossAmount, double discountAmount,
                               double netAmount, double cogs, double profit, String appliedRule) {
    }

    // ------------------------------------------------------------------ 感知通道

    /** 价格规则的总乘数（多条价格规则连乘）。 */
    public double priceMultiplier(RuleSet set) {
        double m = 1.0;
        for (RuleSpec r : set.ofType(RuleType.PRICE)) {
            m *= r.getDouble("multiply", 1.0);
        }
        return m;
    }

    public double priceAdd(RuleSet set) {
        double a = 0;
        for (RuleSpec r : set.ofType(RuleType.PRICE)) {
            a += r.getDouble("add", 0);
        }
        return a;
    }

    /** 有效单价 = 挂牌价 × 乘数 + 加项（下限 0.01，避免出现 0 或负价让对数特征爆炸）。 */
    public double effectiveUnitPrice(RuleSet set, double listPrice) {
        return Math.max(0.01, listPrice * priceMultiplier(set) + priceAdd(set));
    }

    /** 概率类规则的总乘数。 */
    public double probabilityMultiplier(RuleSet set) {
        double m = 1.0;
        for (RuleSpec r : set.ofType(RuleType.PROBABILITY)) {
            m *= r.getDouble("multiplier", 1.0);
        }
        return Math.max(0, m);
    }

    /**
     * 感知折扣率：给定「客户典型客单价」，这套规则在他眼里相当于打了多少折。
     *
     * <p><b>这是本项目里最需要向面试官解释清楚的一个近似</b>：
     * 满减是「篮子门槛」型规则（满 100 减 10），而行为模型是「单个商品」级的概率模型，
     * 两者粒度不同。直接用单个商品价格去判断满减是否触发，会让所有单价低于门槛的商品
     * 都「享受不到优惠」—— 模型对「满减门槛从 100 降到 80」将毫无反应。
     * 所以这里用<b>客户的历史平均客单价</b>去判断他是否够得着门槛，
     * 得到的折扣率作为「他这一单感知到的优惠力度」喂给行为模型。
     *
     * <p>这个近似的后果是明确的、可讨论的：门槛下降会让更多客户「够得着」优惠，
     * 感知折扣率上升，购买概率上升 —— 这正是满减策略在现实中的主要作用机制。
     * 它牺牲的是「同一客户对不同商品的差异化感知」，这在第一版里可以接受，
     * 并且在实验结果里会以「感知折扣率变化」的独立指标暴露出来供审计。
     *
     * @param basketValue 客户典型客单价（历史平均行金额）
     */
    public double perceivedDiscountRate(RuleSet set, double basketValue) {
        double best = 0;
        for (RuleSpec r : set.ofType(RuleType.DISCOUNT)) {
            double threshold = r.getDouble("threshold", 0);
            if (threshold > 0 && basketValue < threshold) {
                continue;
            }
            double cut = r.has("percent")
                    ? basketValue * r.getDouble("percent", 0)
                    : r.getDouble("amount", 0);
            if (basketValue > 0) {
                best = Math.max(best, cut / basketValue);
            }
        }
        // 上限 0.9：任何规则都不该把价格打到一折以下，否则也会让 log(价格) 特征失真
        return Math.min(best, 0.9);
    }

    // ------------------------------------------------------------------ 结算通道

    /**
     * 对购物篮结算。
     *
     * @param grossMargin 毛利率（成本 = 挂牌价 × (1 − 毛利率)）
     */
    public PricedBasket price(RuleSet set, List<BasketLine> lines, double grossMargin) {
        List<PricedLine> priced = new ArrayList<>(lines.size());
        double gross = 0;
        double cogs = 0;
        double mult = priceMultiplier(set);
        double add = priceAdd(set);

        for (BasketLine l : lines) {
            double effective = Math.max(0.01, l.listPrice() * mult + add);
            double amount = effective * l.quantity();
            priced.add(new PricedLine(l.productIndex(), l.quantity(), l.listPrice(), effective, amount));
            gross += amount;
            cogs += l.listPrice() * l.quantity() * (1 - grossMargin);
        }

        double discount = 0;
        StringBuilder applied = new StringBuilder();
        for (RuleSpec r : set.ofType(RuleType.DISCOUNT)) {
            double threshold = r.getDouble("threshold", 0);
            if (gross < threshold) {
                continue;
            }
            double cut = r.has("percent")
                    ? gross * r.getDouble("percent", 0)
                    : r.getDouble("amount", 0);
            if (cut > discount) {
                discount = cut;
                applied.setLength(0);
                applied.append(r.has("percent")
                        ? ("满" + fmt(threshold) + "打" + fmt(r.getDouble("percent", 0) * 100) + "%折")
                        : ("满" + fmt(threshold) + "减" + fmt(cut)));
            }
        }
        // 优惠不能超过订单金额本身（防御性：规则校验已挡一层，这里是最后一道）
        discount = Math.min(discount, gross);
        double net = gross - discount;
        return new PricedBasket(priced, gross, discount, net, cogs, net - cogs, applied.toString());
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
