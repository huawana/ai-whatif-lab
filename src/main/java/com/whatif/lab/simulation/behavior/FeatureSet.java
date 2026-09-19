package com.whatif.lab.simulation.behavior;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 特征集（运行时）—— <b>训练与推理唯一的特征口径来源</b>，由一份 {@link FeatureSetSpec} 配置驱动。
 *
 * <p><b>为什么把变换与特征计算都收在这一个类里</b>：踩过一个真实的坑 ——
 * 仿真引擎的热循环为了省算力，把 z 值拆成"可预计算的部分"，拆的时候忘了套 log1p：
 * 朴素路径喂 log1p(消费额)，快路径直接喂了原始值（40991）。消费额系数是 −1.98，
 * 乘上四万多的原始值，z 直接飞到负无穷 → 购买概率归零 → <b>GMV 全是 0，而系统一切"正常"</b>。
 * 抓出它的是引擎里那条 fastVsNaiveMaxDiff 交叉校验（偏差 0.9994，本该是 1e-15）。
 *
 * <p>根治办法（本类的两条硬约束）：
 * <ol>
 *   <li><b>原始值 + 变换只在这里定义一次</b>（{@code raw()} 与 {@code apply()}），
 *       引擎与训练器都不允许内联数学运算；</li>
 *   <li>{@link #build} 的实现就是"逐维调用 {@link #value}" ——
 *       也就是说"一次性算整个向量"和"一次算一维"在代码上是同一份逻辑，
 *       不可能出现"两条路径口径分叉"。</li>
 * </ol>
 */
public final class FeatureSet {

    /** 特征作用域 —— 决定引擎能否预计算（见 {@link FeatureSetSpec#TYPE_SCOPE}）。 */
    public enum Scope {
        PRICE, DISCOUNT, CUSTOMER, PRODUCT, PAIR
    }

    private final FeatureSetSpec spec;
    private final List<String> names;
    private final Scope[] scopes;

    public FeatureSet(FeatureSetSpec spec) {
        this.spec = spec;
        List<String> n = new ArrayList<>();
        List<Scope> s = new ArrayList<>();
        for (FeatureSetSpec.FeatureDef d : spec.features()) {
            n.add(d.name());
            s.add(Scope.valueOf(scopeOfType(d.type())));
        }
        this.names = List.copyOf(n);
        this.scopes = s.toArray(new Scope[0]);
    }

    public String name() {
        return spec.featureSet();
    }

    public FeatureSetSpec spec() {
        return spec;
    }

    public List<String> names() {
        return names;
    }

    public int dimension() {
        return names.size();
    }

    public Scope scopeOf(int index) {
        return scopes[index];
    }

    /** 某个作用域下的特征下标（引擎按作用域预计算时用）。 */
    public int[] indicesOf(Scope scope) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < scopes.length; i++) {
            if (scopes[i] == scope) {
                out.add(i);
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** 每个作用域各有哪些特征（用于接口回显，让人一眼看出"哪些特征能被预计算"）。 */
    public Map<String, List<String>> scopeBreakdown() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Scope s : Scope.values()) {
            List<String> ns = new ArrayList<>();
            for (int i : indicesOf(s)) {
                ns.add(names.get(i));
            }
            if (!ns.isEmpty()) {
                out.put(s.name(), ns);
            }
        }
        return out;
    }

    /** 单维特征值（未标准化）—— 引擎的快路径与朴素路径都从这里取值。 */
    public double value(int index, FeatureContext ctx, double effectiveUnitPrice, double perceivedDiscount) {
        FeatureSetSpec.FeatureDef d = spec.features().get(index);
        return apply(d.transform(), raw(d.type(), ctx, effectiveUnitPrice, perceivedDiscount), d);
    }

    /**
     * 完整特征向量（未标准化）。
     *
     * <p>实现刻意写成"逐维调用 value"—— 与 {@link #value} 共用同一份逻辑，
     * 于是"整体算"和"分开算"不可能出现口径分叉（这正是 GMV=0 那类 bug 的根因）。
     */
    public double[] build(FeatureContext ctx, double effectiveUnitPrice, double perceivedDiscount) {
        double[] v = new double[names.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = value(i, ctx, effectiveUnitPrice, perceivedDiscount);
        }
        return v;
    }

    public static String scopeOfType(String type) {
        String scope = FeatureSetSpec.TYPE_SCOPE.get(type);
        if (scope == null) {
            throw new IllegalArgumentException("未知特征类型: " + type
                    + "（可用: " + FeatureSetSpec.TYPE_SCOPE.keySet() + "）");
        }
        return scope;
    }

    // ------------------------------------------------------------------ 原始值

    /**
     * 取特征的"原始值"（还没做变换）。
     *
     * <p>下表就是整套特征词汇表的全部内容 —— 平台能表达哪些特征，看这一个 switch 就够了：
     * <pre>
     *   unitPrice           PRICE     规则作用后的有效单价
     *   discountRate        DISCOUNT  规则作用后的感知折扣率
     *   entityEvents        CUSTOMER  客户历史事件数
     *   entityQuantity      CUSTOMER  客户历史数量合计
     *   entityAmount        CUSTOMER  客户历史金额合计
     *   entityAvgAmount     CUSTOMER  客户单次均额
     *   entityRecencyDays   CUSTOMER  最近活跃距今天数
     *   entityTenureDays    CUSTOMER  首次出现距今天数
     *   itemEvents          PRODUCT   商品历史事件数（热门度）
     *   itemQuantity        PRODUCT   商品历史销量
     *   itemPrice           PRODUCT   商品历史均价
     *   itemTypicalQuantity PRODUCT   商品典型购买量
     *   pairEvents          PAIR      客户×商品历史交互次数
     *   pairShare           PAIR      该商品占客户历史的比重
     * </pre>
     */
    private static double raw(String type, FeatureContext c, double effectiveUnitPrice, double perceivedDiscount) {
        return switch (type) {
            case "unitPrice" -> effectiveUnitPrice;
            case "discountRate" -> perceivedDiscount;
            case "entityEvents" -> c.customerEvents();
            case "entityQuantity" -> c.customerQuantity();
            case "entityAmount" -> c.customerAmount();
            case "entityAvgAmount" -> c.customerAvgAmount();
            case "entityRecencyDays" -> c.customerRecencyDays();
            case "entityTenureDays" -> c.customerTenureDays();
            case "itemEvents" -> c.productEvents();
            case "itemQuantity" -> c.productQuantity();
            case "itemPrice" -> c.productAvgPrice();
            case "itemTypicalQuantity" -> c.productTypicalQuantity();
            case "pairEvents" -> c.pairCount();
            case "pairShare" -> c.customerEvents() <= 0 ? 0.0 : c.pairCount() / c.customerEvents();
            default -> throw new IllegalArgumentException("未知特征类型: " + type);
        };
    }

    // ------------------------------------------------------------------ 变换

    /**
     * 变换 —— <b>全项目唯一的数学变换定义点</b>（除了这里不许再出现第二份）。
     *
     * <p>为什么每个特征都必须过这里、而不是"需要时随手写个 Math.log"：
     * 只要允许内联，快路径与朴素路径就会分叉，而分叉不会报错 —— 只会算出错的概率。
     * 结构断言脚本里有一条专门检查 {@code Math.log1p(} 只出现在一个文件里，就是守这条。
     */
    static double apply(String transform, double v, FeatureSetSpec.FeatureDef d) {
        return switch (transform) {
            case "identity" -> v;
            // log1p：ln(1+x)，对"次数/金额"这类长尾量取对数，天然把 0 映射到 0
            case "log1p" -> Math.log1p(v);
            // log：对价格取对数（价格的影响按比例而非绝对值），价格有下限兜底避免 log(0)
            case "log" -> Math.log(Math.max(v, d.epsilon() == null ? 0.01 : d.epsilon()));
            case "sqrt" -> Math.sqrt(Math.max(v, 0.0));
            // recency：1/(1+天数/半衰期) —— 衰减中的活跃度，天然落在 (0,1]
            case "recency" -> 1.0 / (1.0 + v / (d.halfLifeDays() == null ? 30.0 : d.halfLifeDays()));
            default -> throw new IllegalArgumentException("未知变换: " + transform
                    + "（可用: " + FeatureSetSpec.ALLOWED_TRANSFORMS + "）");
        };
    }
}
