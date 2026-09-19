package com.whatif.lab.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 列映射的确定性建议器（不依赖大模型）。
 *
 * <p><b>为什么先做确定性版本、再让 AI 兜底</b>：
 * 表头到标准字段的映射对绝大多数字段是**可枚举**的（uid/customer_id/custid 一路）。
 * 用代码写死这套同义词表，命中时准确率 100%、零成本、零延迟、可单测；
 * 只有代码判不出来的时候（列名是 "col3"、或行业黑话）才需要交给模型。
 * 这就是「规则先跑，模型只处理残差」在数据接入环节的具体落法。
 *
 * <p>每个字段的置信度也一并返回，前端可以把低置信度的项高亮出来让用户重点确认。
 */
public final class MappingSuggester {

    /** 标准字段 → 同义词（全部小写、去掉非字母数字）。 */
    private static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();

    static {
        SYNONYMS.put(ColumnMapping.ENTITY_ID, List.of("customerid", "customer", "userid", "uid", "custid", "memberid", "playerid", "buyerid", "user"));
        SYNONYMS.put(ColumnMapping.PRODUCT_ID, List.of("stockcode", "productid", "product", "item", "itemid", "sku", "goodsid", "prodid", "itemcode"));
        SYNONYMS.put(ColumnMapping.EVENT_TIME, List.of("invoicedate", "timestamp", "eventtime", "time", "date", "datetime", "orderdate", "createdat", "occurredat"));
        SYNONYMS.put(ColumnMapping.QUANTITY, List.of("quantity", "qty", "count", "num", "numitems", "amountofitems", "quantitysold"));
        SYNONYMS.put(ColumnMapping.UNIT_PRICE, List.of("unitprice", "price", "priceunit", "cost", "unitcost", "单价"));
        SYNONYMS.put(ColumnMapping.ORDER_ID, List.of("invoiceno", "invoice", "orderid", "orderno", "order", "transactionid", "billno"));
        SYNONYMS.put(ColumnMapping.DESCRIPTION, List.of("description", "desc", "productname", "name", "title", "itemname"));
        SYNONYMS.put(ColumnMapping.GROUP, List.of("country", "region", "market", "city", "channel", "province"));
    }

    private MappingSuggester() {
    }

    public record Suggestion(ColumnMapping mapping, Map<String, String> confidence, Map<String, String> evidence) {
    }

    /**
     * 对表头做建议。
     *
     * <p>匹配优先级：完全相等 → 同义词命中 → 子串包含。
     * 三级依次降置信度，绝不因为「模糊像」就给出高置信度 ——
     * 一个 0.6 置信度的映射如果被当作 1.0 用，就是数据错位的起点。
     */
    public static Suggestion suggest(List<String> header) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, String> confidence = new LinkedHashMap<>();
        Map<String, String> evidence = new LinkedHashMap<>();

        List<String> normalized = header.stream().map(MappingSuggester::norm).toList();

        for (Map.Entry<String, List<String>> e : SYNONYMS.entrySet()) {
            String field = e.getKey();
            String best = null;
            double bestScore = 0;
            String why = null;

            for (int i = 0; i < header.size(); i++) {
                String n = normalized.get(i);
                if (n.isEmpty()) {
                    continue;
                }
                List<String> syns = e.getValue();
                double score = 0;
                String reason = null;
                if (syns.contains(n)) {
                    score = 1.0;
                    reason = "表头 " + header.get(i) + " 命中同义词表";
                } else {
                    for (String syn : syns) {
                        if (n.contains(syn) || syn.contains(n)) {
                            // 子串匹配：stockcode 里含 code，col1 里什么也不含 —— 但 "price" 与
                            // "price_after_discount" 都含 price，会歧义，所以只给 0.6
                            if (n.length() >= 3) {
                                score = Math.max(score, 0.6);
                                reason = "表头 " + header.get(i) + " 与同义词 " + syn + " 部分匹配";
                            }
                        }
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = header.get(i);
                    why = reason;
                }
            }
            if (best != null) {
                mapping.put(field, best);
                confidence.put(field, String.valueOf(bestScore));
                evidence.put(field, why);
            }
        }
        return new Suggestion(new ColumnMapping(mapping), confidence, evidence);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
    }
}
