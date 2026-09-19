package com.whatif.lab.ai.schema;

import com.whatif.lab.data.ColumnMapping;
import com.whatif.lab.data.extract.DomainSpec;
import com.whatif.lab.data.profile.ColumnProfile;
import com.whatif.lab.data.profile.DataProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色 → 领域配置的**确定性推导** —— 这一步不许有 LLM 参与。
 *
 * <p><b>为什么分离</b>：如果让 LLM 直接写领域配置（skip/classify/amount/fieldTypes 这一堆），
 * 它能弄坏的面是"整个执行语义"，而且错法无穷（少一个 skip、金额算子写反、类型写成 string…）。
 * 收窄成"只判角色"之后：LLM 的输出面只有 17 个枚举值，判错会被校验器和黄金集挡住，
 * 而"角色→配置"这段映射是纯代码，可以单测、可以逐条讲清。
 *
 * <p>推导出的每条规则都必须**能追溯到画像事实**，例如"数量列有负值 → 生成 quantity&lt;0 归为 negative"，
 * 而不是凭经验硬塞一条。
 */
@Component
public class RoleDeriver {

    /** 推导结果：领域配置 + 标准字段到源列的映射（两者必须同时给出，否则配置落不了地）。 */
    public record Derived(DomainSpec spec, Map<String, String> fieldToColumn, List<String> notes) {
    }

    /** 事件类型列承载用的标准字段名（ColumnMapping 是开放 Map，引擎按 referencedFields 取值）。 */
    public static final String EVENT_TYPE_FIELD = "eventType";

    public Derived derive(String domain, String description, List<RoleAssignment> roles, DataProfile profile) {
        return derive(domain, description, roles, profile, List.of(), List.of());
    }

    public Derived derive(String domain, String description, List<RoleAssignment> roles, DataProfile profile,
                          List<String> positiveEventValues, List<String> negativeEventValues) {
        Map<String, String> mapping = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        // ---- ① 标准字段映射：角色自带落点（ENTITY_ID→entityId …），直接搬 ----
        for (RoleAssignment a : roles) {
            String field = a.role().canonicalField();
            if (field != null && !mapping.containsKey(field)) {
                mapping.put(field, a.column());
            }
        }

        boolean hasQty = mapping.containsKey(ColumnMapping.QUANTITY);
        boolean hasPrice = mapping.containsKey(ColumnMapping.UNIT_PRICE);
        boolean hasAmount = mapping.containsKey("amount");

        // ---- ② 数量与金额的两种形态：要么 数量×单价，要么 原生金额列 ----
        DomainSpec.AmountRule amount;
        if (hasQty && hasPrice) {
            amount = new DomainSpec.AmountRule("multiply", List.of(ColumnMapping.QUANTITY), List.of(ColumnMapping.UNIT_PRICE));
        } else if (hasAmount) {
            // multiply + 空 abs + 单个 with == 取该列本身（引擎里 Π空 × Π{amount} = amount），
            // 所以不用给引擎加新算子就能表达"原生金额列"
            amount = new DomainSpec.AmountRule("multiply", List.of(), List.of("amount"));
            notes.add("金额取自原生列 " + mapping.get("amount") + "（未用 数量×单价 推导）");
        } else {
            amount = new DomainSpec.AmountRule("none", List.of(), List.of());
            notes.add("没有金额可算：既没有 数量×单价，也没有原生金额列");
        }

        // ---- ③ 必需字段：只声明**真的用到的**，否则抽取器会因为"缺必需列"直接拒绝 ----
        List<String> required = new ArrayList<>(List.of(ColumnMapping.ENTITY_ID, ColumnMapping.PRODUCT_ID,
                ColumnMapping.EVENT_TIME));
        if (hasQty) {
            required.add(ColumnMapping.QUANTITY);
        }
        if (hasPrice) {
            required.add(ColumnMapping.UNIT_PRICE);
        }

        // ---- ④ 跳过规则：脏行必须计数，绝不静默丢 ----
        List<DomainSpec.Condition> skip = new ArrayList<>();
        skip.add(new DomainSpec.Condition(ColumnMapping.ENTITY_ID, "blank", null, "缺少主体ID"));
        skip.add(new DomainSpec.Condition(ColumnMapping.PRODUCT_ID, "blank", null, "缺少对象ID"));
        skip.add(new DomainSpec.Condition(ColumnMapping.EVENT_TIME, "unparsable", null, "时间无法解析"));

        // ---- ⑤ 事件分类：**由画像事实驱动** —— 数量列真有负值才生成这条规则 ----
        // 先声明 fieldTypes：下面的 ⑤b（事件类型列）也要往里写类型。
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        List<DomainSpec.Classification> classify = new ArrayList<>();
        ColumnProfile qtyCol = col(profile, mapping.get(ColumnMapping.QUANTITY));
        if (qtyCol != null && qtyCol.negativeCount() > 0) {
            classify.add(new DomainSpec.Classification(ColumnMapping.QUANTITY, "lt", 0, "negative"));
            notes.add("数量列有 " + qtyCol.negativeCount() + " 个负值 → 生成 数量<0 归为 negative 的分类（退货惯例）");
        }

        // ---- ⑤b 事件类型列：把"哪些取值算成交"变成 classify 规则 ----
        // 这是点击流数据的关键：RetailRocket 里 view/addtocart/transaction 三种事件混在一张表，
        // 不区分的话"购买"就无从定义（实测该表只有 transaction 行才有 transactionid）。
        String defaultBucket = "positive";
        List<RoleAssignment> eventTypeRoles = roles.stream()
                .filter(r -> r.role() == ColumnRole.EVENT_TYPE).toList();
        if (!eventTypeRoles.isEmpty()) {
            defaultBucket = "neutral";   // 有事件类型列时，默认认为"不是成交"（如 view）
            mapping.put(EVENT_TYPE_FIELD, eventTypeRoles.get(0).column());
            fieldTypes.put(EVENT_TYPE_FIELD, "string");
            if (!positiveEventValues.isEmpty()) {
                classify.add(new DomainSpec.Classification(EVENT_TYPE_FIELD, "in", positiveEventValues, "positive"));
                notes.add("事件类型 " + positiveEventValues + " 归为 positive（成交）");
            }
            if (!negativeEventValues.isEmpty()) {
                classify.add(new DomainSpec.Classification(EVENT_TYPE_FIELD, "in", negativeEventValues, "negative"));
                notes.add("事件类型 " + negativeEventValues + " 归为 negative（取消/退货）");
            }
            if (positiveEventValues.isEmpty()) {
                notes.add("有事件类型列但没指定哪种取值算成交 → 所有事件都会被算作 neutral（需人工/LLM 指定）");
            }
        }

        // ---- ⑥ 字段类型：按画像推断的类型定 int/decimal，避免"1.5 被静默截断成 1" ----
        fieldTypes.put(ColumnMapping.EVENT_TIME, "time");
        fieldTypes.put(ColumnMapping.QUANTITY, qtyCol != null && "INT".equals(qtyCol.inferredType()) ? "int" : "decimal");
        fieldTypes.put(ColumnMapping.UNIT_PRICE, "decimal");
        if (hasAmount) {
            fieldTypes.put("amount", "decimal");
        }

        // ---- ⑦ 实体属性：分类列 → 主体属性；描述列 → 对象属性 ----
        List<DomainSpec.EntityAttribute> attrs = new ArrayList<>();
        List<RoleAssignment> cats = roles.stream()
                .filter(r -> r.role() == ColumnRole.CATEGORY || r.role() == ColumnRole.COVARIATE).toList();
        if (!cats.isEmpty()) {
            attrs.add(new DomainSpec.EntityAttribute("main", "group", ColumnMapping.GROUP, "string"));
        }
        if (mapping.containsKey(ColumnMapping.DESCRIPTION)) {
            attrs.add(new DomainSpec.EntityAttribute("item", "description", ColumnMapping.DESCRIPTION, "string"));
        }

        String main = stem(mapping.get(ColumnMapping.ENTITY_ID), "Customer");
        String item = stem(mapping.get(ColumnMapping.PRODUCT_ID), "Product");

        DomainSpec spec = new DomainSpec(domain, description, null,
                Map.of("main", main, "item", item),
                null, required, fieldTypes, skip, classify, defaultBucket, true, amount, attrs);
        return new Derived(spec, mapping, notes);
    }

    /** 列名 → 实体类型名（CustomerID → Customer）。只是给人看的标签，不参与计算。 */
    private static String stem(String column, String fallback) {
        if (column == null || column.isBlank()) {
            return fallback;
        }
        String s = column.replaceAll("(?i)[_\\s-]?(id|no|code|key)$", "").trim();
        s = s.replaceAll("[_\\s-]+", " ").trim();
        if (s.isEmpty()) {
            return fallback;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static ColumnProfile col(DataProfile p, String name) {
        if (name == null) {
            return null;
        }
        return p.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }
}
