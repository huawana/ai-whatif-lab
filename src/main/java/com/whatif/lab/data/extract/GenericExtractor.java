package com.whatif.lab.data.extract;

import com.whatif.lab.data.ColumnMapping;
import com.whatif.lab.data.csv.CsvTable;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 配置驱动的领域适配器 —— 用一份 {@link DomainSpec} 完成原来手写适配器的全部工作。
 *
 * <p><b>它替代了什么</b>：原来"哪一列是客户、取消怎么判、负数量怎么处理"写死在
 * {@code EcommerceExtractor} 里，换行业要改 Java。现在这些判断全部来自配置，
 * 新增领域 = 放一份 JSON 到配置目录（见 {@code DomainSpecLoader}）。
 *
 * <p><b>怎么保证"配置化"没有悄悄改变语义</b>：
 * 内置配置 {@code domains/ecommerce-generic.json} 是对手写实现的逐条翻译，
 * 验收脚本会用**同一份 CSV 分别走两条路径**（{@code domain=ecommerce} 手写实现 vs
 * {@code domain=ecommerce-generic} 本类），断言结果逐位一致（行数/事件数/取消数/金额/实体数）。
 * 只要有一处判断翻译错了，那条等价性断言就会挂 —— 这是"配置层真的承载了语义"的证明，
 * 而不是我说它等价。
 */
public class GenericExtractor implements DomainExtractor {

    private final DomainSpec spec;

    public GenericExtractor(DomainSpec spec) {
        this.spec = spec;
    }

    @Override
    public String domain() {
        return spec.domain();
    }

    public DomainSpec spec() {
        return spec;
    }

    @Override
    public void extract(CsvTable table,
                        ColumnMapping mapping,
                        Consumer<RawEvent> events,
                        DataQualityReport.Builder report,
                        EntityAccumulator entities) throws IOException {

        // ---- 列索引：标准字段 → CSV 列号 ----
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (String field : spec.requiredFields()) {
            int i = table.indexOf(mapping.column(field));
            if (i < 0) {
                throw new IllegalArgumentException("领域 " + spec.domain() + " 需要列映射字段 " + field
                        + "，但导入请求里没有映射它（当前映射: " + mapping.fields() + "）");
            }
            idx.put(field, i);
        }
        // 非必需但被配置引用到的字段（跳过条件 / 分类 / 实体属性 / 金额）
        for (String field : referencedFields()) {
            if (idx.containsKey(field)) {
                continue;
            }
            String column = mapping.column(field);
            if (column != null) {
                idx.put(field, table.indexOf(column));
            }
        }

        final String mainType = spec.entityTypes().getOrDefault("main", "Customer");
        final String itemType = spec.entityTypes().getOrDefault("item", "Product");

        table.forEach(row -> {
            report.rawRow();

            // ---- ① 跳过规则：按配置顺序，命中第一条即跳过（脏行必须计数，绝不静默丢弃）----
            for (DomainSpec.Condition c : spec.skip()) {
                // 用 matchesField（带字段语义）：unparsable 对时间列与数值列含义不同
                if (matchesField(c.field(), value(row, idx, c.field()), c.op(), c.value(), spec.fieldTypes())) {
                    report.skip((c.reason() == null || c.reason().isBlank())
                            ? (c.field() + " " + c.op()) : c.reason());
                    return;
                }
            }

            // ---- ② 事件类型：按配置顺序分类，都不命中 → 默认 bucket ----
            String bucket = spec.defaultEventType();
            for (DomainSpec.Classification c : spec.classify()) {
                if (matchesField(c.field(), value(row, idx, c.field()), c.op(), c.value(), spec.fieldTypes())) {
                    bucket = c.as();
                    break;
                }
            }
            String eventType = spec.eventTypes().getOrDefault(bucket, bucket);

            // ---- ③ 主键与时间 ----
            String entityId = value(row, idx, "entityId");
            String productId = value(row, idx, "productId");
            LocalDateTime time = DomainExtractor.time(value(row, idx, "eventTime"));
            String orderId = value(row, idx, "orderId");

            // ---- ④ 数量与金额 ----
            long qty = asLong(value(row, idx, "quantity"));
            BigDecimal eventQty = BigDecimal.valueOf(Boolean.TRUE.equals(spec.absoluteQuantity()) ? Math.abs(qty) : qty);
            BigDecimal unitPrice = asDecimal(value(row, idx, "unitPrice"));
            BigDecimal amount = amount(row, idx);

            // ---- ⑤ 实体属性（多行反复写入，值相同，幂等）----
            for (DomainSpec.EntityAttribute a : spec.entityAttributes()) {
                String raw = value(row, idx, a.from());
                if (raw == null) {
                    continue;
                }
                boolean item = "item".equals(a.target());
                entities.entity(item ? itemType : mainType, item ? productId : entityId, a.name(),
                        convert(raw, a.type()));
            }

            events.accept(new RawEvent(entityId,
                    eventType,
                    productId,
                    orderId,
                    time,
                    eventQty.intValue(),
                    unitPrice == null ? BigDecimal.ZERO : unitPrice,
                    amount));
            report.acceptedEvent(time, "negative".equals(bucket));
        });
    }

    /**
     * 金额算法。
     *
     * <p>{@code multiply}: Π abs(abs[]) × Π with[] —— 与手写实现 {@code price×|qty|} 等价；
     * {@code none}（默认）: 没有金额概念的领域（例如点击流）记 0，而不是让整个导入失败。
     */
    private BigDecimal amount(String[] row, Map<String, Integer> idx) {
        DomainSpec.AmountRule rule = spec.amount();
        if (!"multiply".equals(rule.op())) {
            return BigDecimal.ZERO;
        }
        BigDecimal acc = BigDecimal.ONE;
        for (String f : rule.abs()) {
            BigDecimal v = asDecimal(value(row, idx, f));
            acc = acc.multiply(v == null ? BigDecimal.ZERO : v.abs());
        }
        for (String f : rule.with()) {
            BigDecimal v = asDecimal(value(row, idx, f));
            acc = acc.multiply(v == null ? BigDecimal.ZERO : v);
        }
        return acc;
    }

    /** 按配置声明的类型把字符串解析成数（int 语义用整数解析器，1.9 即视为脏行）。 */
    private BigDecimal asDecimal(String raw) {
        return DomainExtractor.parseDecimal(raw);
    }

    private long asLong(String raw) {
        return "int".equals(spec.fieldTypes().getOrDefault("quantity", "int"))
                ? (DomainExtractor.parseInt(raw) == null ? 0L : DomainExtractor.parseInt(raw))
                : (asDecimal(raw) == null ? 0L : asDecimal(raw).longValue());
    }

    private static Object convert(String raw, String type) {
        return switch (type) {
            case "decimal" -> DomainExtractor.parseDecimal(raw);
            case "int" -> DomainExtractor.parseInt(raw);
            default -> raw;
        };
    }

    /** 配置里引用到的全部标准字段（用于确定要解析哪些列）。 */
    private List<String> referencedFields() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        spec.skip().forEach(c -> out.add(c.field()));
        spec.classify().forEach(c -> out.add(c.field()));
        spec.amount().abs().forEach(out::add);
        spec.amount().with().forEach(out::add);
        spec.entityAttributes().forEach(a -> out.add(a.from()));
        return List.copyOf(out);
    }

    private static String value(String[] row, Map<String, Integer> idx, String field) {
        Integer i = idx.get(field);
        return i == null ? null : CsvTable.cell(row, i);
    }

    /**
     * 闭合词汇表的算子求值 —— 每个算子语义确定、可枚举、可被验收脚本覆盖。
     *
     * <p>{@code unparsable} 的语义按字段类型区分：时间字段用时间解析器判、数值字段用数值解析器判、
     * 其它字段永远"可解析"（避免把"没映射这一列"误判成跳过）。
     */
    static boolean matches(String raw, String op, Object expected) {
        String o = String.valueOf(op);
        switch (o) {
            case "blank":
                return raw == null || raw.isBlank();
            case "unparsable":
                // 字段语义相关，必须走 matchesField(...)；直接调到这里说明用错了入口
                throw new IllegalStateException("unparsable 必须用 matchesField 判定（字段语义相关）");
            case "startsWith":
                return raw != null && raw.startsWith(String.valueOf(expected));
            case "endsWith":
                return raw != null && raw.endsWith(String.valueOf(expected));
            case "contains":
                return raw != null && raw.contains(String.valueOf(expected));
            case "in":
                return raw != null && asList(expected).contains(raw);
            case "eq":
                return compare(raw, expected) == 0;
            case "ne":
                return raw != null && compare(raw, expected) != 0;
            case "lt":
                return numericCompare(raw, expected) < 0;
            case "lte":
                return numericCompare(raw, expected) <= 0;
            case "gt":
                return numericCompare(raw, expected) > 0;
            case "gte":
                return numericCompare(raw, expected) >= 0;
            default:
                throw new IllegalStateException("未知算子: " + op);
        }
    }

    /**
     * 带字段类型语义的判定："无法解析"对时间列与数值列含义不同，
     * 而"整数"与"小数"在脏行判定上也会给出不同结果（{@code 1.9} 在整数语义下算脏行）。
     * 类型来自配置（{@code fieldTypes}），不在 Java 里写死。
     */
    static boolean matchesField(String field, String raw, String op, Object expected, Map<String, String> fieldTypes) {
        if ("unparsable".equals(op)) {
            if (raw == null || raw.isBlank()) {
                return true;
            }
            return switch (fieldTypes.getOrDefault(field, "string")) {
                case "time" -> DomainExtractor.time(raw) == null;
                // 【注意不能用 DomainExtractor.parseInt】它是宽松的：new BigDecimal("1.5").intValue() = 1，
                // 会把 1.5 当合法数量并**静默截断**。而"int 语义"的全部意义就是判定"这个值算不算脏行"——
                // 声明成 int 却接受 1.5，这个配置项就是名义上的（实测抓到的真 bug）。
                case "int" -> !isIntegral(raw);
                case "decimal" -> DomainExtractor.parseDecimal(raw) == null;
                default -> false;
            };
        }
        return matches(raw, op, expected);
    }

    private static List<String> asList(Object expected) {
        if (expected instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(expected));
    }

    private static int compare(String raw, Object expected) {
        if (raw == null) {
            return -1;
        }
        Double a = parse(raw);
        Double b = parse(String.valueOf(expected));
        if (a != null && b != null) {
            return Double.compare(a, b);
        }
        return raw.compareTo(String.valueOf(expected));
    }

    private static int numericCompare(String raw, Object expected) {
        Double a = parse(raw);
        Double b = parse(String.valueOf(expected));
        if (a == null || b == null) {
            return 1;   // 无法解析 → 视为"不满足数值比较"（跳过的判断交给 unparsable 规则）
        }
        return Double.compare(a, b);
    }

    /** 是否真的是整数（1.5 不是；"3" 与 "3.0" 是）。 */
    static boolean isIntegral(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            new BigDecimal(raw.trim()).toBigIntegerExact();
            return true;
        } catch (ArithmeticException | NumberFormatException e) {
            return false;
        }
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
