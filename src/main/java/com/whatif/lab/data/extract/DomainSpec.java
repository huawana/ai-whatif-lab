package com.whatif.lab.data.extract;

import java.util.List;
import java.util.Map;

/**
 * 领域配置 —— 把「一个行业的数据长什么样」从 Java 代码搬进一份 JSON。
 *
 * <p><b>为什么要有它</b>：原来换行业必须改 Java（{@code EcommerceExtractor} 里写死了"哪一列是客户、
 * 取消怎么判、负数量怎么处理"）。对使用者来说，这意味着"想试自己的数据 → 得先读懂并修改源码"，
 * 一个平台如果需要使用者会写代码才能接入，它就不能被真正复用。
 * 目标：<b>新增一个领域 = 丢一份 JSON 到配置目录 + 重启</b>（外部目录，不需要重新打包、不需要改一行 Java）。
 *
 * <p><b>为什么是「闭合词汇表」而不是通用表达式</b>：
 * 允许自由表达式（SpEL / JS / 任意脚本）看起来很灵活，但会把三样东西一起丢掉 ——
 * ① 可测性（配置能表达什么就没法枚举，验收脚本写不出来）；
 * ② 安全性（配置成了可执行代码）；
 * ③ 可解释性（出错时你无法说清"这份配置到底会做什么"）。
 * 所以 op 只有下面这些，每个都是确定语义、可被验证脚本枚举的。
 *
 * <p><b>字段名引用的是「标准字段」</b>（{@code entityId / productId / eventTime / quantity /
 * unitPrice / orderId / description / group}，见 {@link com.whatif.lab.data.ColumnMapping}），
 * 而不是 CSV 里的原始列名 —— 原始列名由列映射负责，两者职责分开。
 *
 * <p>配置字段手册见 {@code domains/README.md}。
 */
public record DomainSpec(
        /** 领域名（唯一标识，导入时要指定它）。 */
        String domain,
        /** 人类可读说明，会出现在接口与文档里。 */
        String description,

        /** 实体类型命名：main=行为主体（客户/玩家/用户），item=被操作对象（商品/道具）。 */
        /**
         * 该领域用哪个特征集（{@code features/<name>.json}）。
         *
         * <p>把"数据怎么解释"与"模型看哪些特征"分成两份配置，是刻意的：
         * 同一个领域可能想做不同角度的假设分析（只看频次 / 加入金额与折扣），
         * 而同一套特征也可能服务多个领域。两份配置各自独立、各自可验收。
         * 不填则用默认特征集（{@code retail-default}），保证老数据集继续可用。
         */
        String features,

        Map<String, String> entityTypes,

        /** 事件类型命名：positive=正向行为，negative=负向行为（不参与购买概率训练），neutral=中性。 */
        Map<String, String> eventTypes,

        /** 必需的标准字段（列映射里必须有映射）。 */
        List<String> requiredFields,

        /**
         * 字段类型语义：int / decimal / time / string。
         *
         * <p>为什么连"这个字段是整数还是小数"都要配置：它直接决定"什么样的值算脏行"。
         * 例如数量列里出现 {@code 1.9} —— 整数语义下这是脏行（不能当件数），
         * 小数语义下它合法。把它写死在 Java 里，就等于把某个行业的假设又搬回来了。
         */
        Map<String, String> fieldTypes,

        /** 跳过规则：按顺序判定，命中第一条即跳过并计数（脏行必须计数，绝不静默丢弃）。 */
        List<Condition> skip,

        /** 事件分类规则：按顺序判定，命中第一条决定事件属于哪个 bucket。 */
        List<Classification> classify,

        /** 都不命中时的 bucket（positive / negative / neutral）。 */
        String defaultEventType,

        /** 事件数量是否取绝对值（原始数据常用负数量表示退货，退货语义由事件类型承载）。 */
        Boolean absoluteQuantity,

        /** 金额算法（multiply = abs(字段1…)×字段2…；none = 没有金额概念，记为 0）。 */
        AmountRule amount,

        /** 实体属性：把行里的列写成实体的属性（同一实体多行反复写入，值相同，幂等）。 */
        List<EntityAttribute> entityAttributes
) {

    /** 没有显式配置时使用的必需字段（与既有 ColumnMapping.missingRequired 保持一致）。 */
    public static final List<String> DEFAULT_REQUIRED =
            List.of("entityId", "productId", "eventTime", "quantity", "unitPrice");

    public static final List<String> BUCKETS = List.of("positive", "negative", "neutral");

    /** 字段类型默认值（对应本项目既有列映射的语义）。 */
    public static final Map<String, String> DEFAULT_FIELD_TYPES = Map.of(
            "quantity", "int",
            "unitPrice", "decimal",
            "eventTime", "time");

    public DomainSpec {
        entityTypes = (entityTypes == null || entityTypes.isEmpty())
                ? Map.of("main", "Customer", "item", "Product")
                : Map.copyOf(entityTypes);
        eventTypes = (eventTypes == null || eventTypes.isEmpty())
                ? Map.of("positive", "PURCHASE", "negative", "CANCEL", "neutral", "EVENT")
                : Map.copyOf(eventTypes);
        requiredFields = (requiredFields == null || requiredFields.isEmpty())
                ? DEFAULT_REQUIRED
                : List.copyOf(requiredFields);
        fieldTypes = (fieldTypes == null || fieldTypes.isEmpty()) ? DEFAULT_FIELD_TYPES : Map.copyOf(fieldTypes);
        skip = skip == null ? List.of() : List.copyOf(skip);
        classify = classify == null ? List.of() : List.copyOf(classify);
        defaultEventType = (defaultEventType == null || defaultEventType.isBlank()) ? "positive" : defaultEventType;
        features = (features == null || features.isBlank()) ? "retail-default" : features;
        absoluteQuantity = absoluteQuantity == null || absoluteQuantity;
        amount = amount == null ? new AmountRule("none", List.of(), List.of()) : amount;
        entityAttributes = entityAttributes == null ? List.of() : List.copyOf(entityAttributes);
    }

    /** 一条跳过规则。op 见 {@code DomainSpecLoader.ALLOWED_OPS}。 */
    public record Condition(String field, String op, Object value, String reason) {
    }

    /** 一条分类规则：满足条件 → 归入 bucket（positive / negative / neutral）。 */
    public record Classification(String field, String op, Object value, String as) {
    }

    /** 金额算法：op=multiply 时 amount = Π abs(abs[]) × Π with[]；op=none 时 amount = 0。 */
    public record AmountRule(String op, List<String> abs, List<String> with) {
        public AmountRule {
            abs = abs == null ? List.of() : List.copyOf(abs);
            with = with == null ? List.of() : List.copyOf(with);
            op = (op == null || op.isBlank()) ? "none" : op;
        }
    }

    /** 实体属性：target=main/item，name=属性名，from=来源标准字段，type=string/decimal/int。 */
    public record EntityAttribute(String target, String name, String from, String type) {
        public EntityAttribute {
            target = (target == null || target.isBlank()) ? "main" : target;
            type = (type == null || type.isBlank()) ? "string" : type;
        }
    }
}
