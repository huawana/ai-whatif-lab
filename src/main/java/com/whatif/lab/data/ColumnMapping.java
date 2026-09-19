package com.whatif.lab.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 列映射：标准字段 → 原始列名。
 *
 * <p>这是「Data Mapping Assistant」的产物：AI（或确定性启发式）猜测用户 CSV 的列含义，
 * 由用户确认后才落库。为什么必须有人确认这一步：
 * 列映射错了（把 unit_price 映射成 quantity）不会抛异常，
 * 只会让行为模型学到一堆垃圾规律，最后指标看着正常但全是错的 ——
 * 这类错误只有靠「让用户看一眼」和「映射可追溯」来防。
 */
public record ColumnMapping(Map<String, String> fields) {

    public static final String ENTITY_ID = "entityId";
    public static final String PRODUCT_ID = "productId";
    public static final String EVENT_TIME = "eventTime";
    public static final String QUANTITY = "quantity";
    public static final String UNIT_PRICE = "unitPrice";
    public static final String ORDER_ID = "orderId";
    public static final String DESCRIPTION = "description";
    public static final String GROUP = "group";

    public ColumnMapping {
        fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
    }

    public String column(String standardField) {
        return fields.get(standardField);
    }

    public ColumnMapping with(String standardField, String columnName) {
        Map<String, String> copy = new LinkedHashMap<>(fields);
        copy.put(standardField, columnName);
        return new ColumnMapping(copy);
    }

    /** 必填字段（电商域）：没有主体、客体、时间就无法构成行为序列。 */
    public java.util.List<String> missingRequired() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String f : java.util.List.of(ENTITY_ID, PRODUCT_ID, EVENT_TIME, QUANTITY, UNIT_PRICE)) {
            if (column(f) == null) {
                missing.add(f);
            }
        }
        return missing;
    }
}
