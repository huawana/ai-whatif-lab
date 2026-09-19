package com.whatif.lab.simulation.behavior;

import java.util.List;
import java.util.Map;

/**
 * 特征集配置 —— 把「模型看哪些特征」从 Java 代码搬进一份 JSON。
 *
 * <p><b>为什么要有它</b>：模型本身（逻辑回归）、训练器、仿真引擎、指标、缓存、AI 全都与领域无关，
 * 真正把平台钉死在零售上的是**特征**：消费额、购买频次、商品流行度、折扣敏感 ——
 * 这些是"零售语义"。把它们写死在代码里，换一个领域就只能跑出无意义的数字。
 *
 * <p><b>设计原则（和领域配置一致）：闭合词汇表</b>
 * <ul>
 *   <li>特征类型（{@code type}）只能从 {@link #TYPE_SCOPE} 里选 —— 每个类型都预先声明了它的
 *       <b>作用域</b>（客户侧 / 商品侧 / 价格 / 折扣 / 成对），引擎才能安全地"按作用域预计算"；</li>
 *   <li>变换（{@code transform}）只能从 {@link #ALLOWED_TRANSFORMS} 里选；</li>
 *   <li>不做通用表达式求值 —— 配置能表达什么必须能被枚举，否则既没法写验收脚本，
 *       也没法保证"引擎的快路径与朴素路径算的是同一个东西"。</li>
 * </ul>
 *
 * <p><b>作用域是这套设计的核心不变量</b>：一个"客户侧"特征必须只读客户侧字段（不依赖价格与折扣），
 * "商品侧"特征必须只读商品侧字段。破坏了它，引擎预计算出来的常数项就是错的 ——
 * 而且不会报错，只会算出错的概率。{@code FeatureSetTest} 里有专门的属性测试守这条不变量。
 */
public record FeatureSetSpec(String featureSet,
                             String description,
                             List<FeatureDef> features) {

    /**
     * 一个特征的定义。
     *
     * @param name        特征名（会落库、会出现在模型权重表里，改名等于换模型）
     * @param type        特征类型（决定"从哪取原始值"与作用域），见 {@link #TYPE_SCOPE}
     * @param transform   变换方式，见 {@link #ALLOWED_TRANSFORMS}
     * @param halfLifeDays 仅 {@code recency} 变换使用：活跃度衰减半衰期（天），默认 30
     * @param epsilon     仅 {@code log} 变换使用：取对数前的下限，默认 0.01（防止 log(0)）
     */
    public record FeatureDef(String name, String type, String transform,
                             Double halfLifeDays, Double epsilon) {
    }

    /**
     * 特征词汇表：类型 → 作用域。
     *
     * <p>作用域含义：
     * <ul>
     *   <li>{@code CUSTOMER} —— 只与客户有关，可在仿真开始前对每个客户算一次；</li>
     *   <li>{@code PRODUCT}  —— 只与商品有关，可对每个商品算一次；</li>
     *   <li>{@code PRICE}    —— 与"规则作用后的有效单价"有关，按商品×每条臂算一次；</li>
     *   <li>{@code DISCOUNT} —— 与"规则作用后的感知折扣"有关，按客户×每条臂算一次
     *                          （这是「规则变化 → 行为变化」的传导通道）；</li>
     *   <li>{@code PAIR}     —— 与客户×商品这一对有关，只能在每个决策处算。</li>
     * </ul>
     */
    public static final Map<String, String> TYPE_SCOPE = Map.ofEntries(
            Map.entry("unitPrice", "PRICE"),
            Map.entry("discountRate", "DISCOUNT"),
            Map.entry("entityEvents", "CUSTOMER"),
            Map.entry("entityQuantity", "CUSTOMER"),
            Map.entry("entityAmount", "CUSTOMER"),
            Map.entry("entityAvgAmount", "CUSTOMER"),
            Map.entry("entityRecencyDays", "CUSTOMER"),
            Map.entry("entityTenureDays", "CUSTOMER"),
            Map.entry("itemEvents", "PRODUCT"),
            Map.entry("itemQuantity", "PRODUCT"),
            Map.entry("itemPrice", "PRODUCT"),
            Map.entry("itemTypicalQuantity", "PRODUCT"),
            Map.entry("pairEvents", "PAIR"),
            Map.entry("pairShare", "PAIR"));

    public static final List<String> ALLOWED_TRANSFORMS =
            List.of("identity", "log1p", "log", "sqrt", "recency");

    /** 没配置特征集时使用的默认名（保持老数据集/老模型可用）。 */
    public static final String DEFAULT_NAME = "retail-default";

    public FeatureSetSpec {
        features = features == null ? List.of() : List.copyOf(features);
    }
}
