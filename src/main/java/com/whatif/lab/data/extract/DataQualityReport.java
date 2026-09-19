package com.whatif.lab.data.extract;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据质量报告。
 *
 * <p>为什么要专门产出这个：AI 或启发式猜的列映射、用户的脏数据，
 * 都会以「悄悄少掉一批行」的形式表现出来。没有这份报告，
 * 你只会看到指标变了，却不知道是因为策略变了还是因为数据少了 3%。
 * 它也是「数据质量」页面的直接数据源。
 */
public record DataQualityReport(int rawRows,
                                int acceptedEvents,
                                int skippedRows,
                                int cancelEvents,
                                int purchaseEvents,
                                int distinctEntities,
                                int distinctProducts,
                                LocalDateTime minTime,
                                LocalDateTime maxTime,
                                Map<String, Integer> skipReasons) {

    public static Builder builder() {
        return new Builder();
    }

    /** 累加器（抽取过程是流式的，只能边读边统计）。 */
    public static final class Builder {
        private int rawRows;
        private int accepted;
        private int cancel;
        private int purchase;
        private int entityUnknownRows;
        private int productUnknownRows;
        private int timeUnknownRows;
        private int invalidQuantity;
        private int nullCustomerRows;
        private LocalDateTime min;
        private LocalDateTime max;
        private final Map<String, Integer> skipReasons = new LinkedHashMap<>();

        public void rawRow() {
            rawRows++;
        }

        public void acceptedEvent(LocalDateTime time, boolean isCancel) {
            accepted++;
            if (isCancel) {
                cancel++;
            } else {
                purchase++;
            }
            if (time != null) {
                if (min == null || time.isBefore(min)) {
                    min = time;
                }
                if (max == null || time.isAfter(max)) {
                    max = time;
                }
            }
        }

        public void skip(String reason) {
            skipReasons.merge(reason, 1, Integer::sum);
        }

        public DataQualityReport build(int distinctEntities, int distinctProducts) {
            int skipped = skipReasons.values().stream().mapToInt(Integer::intValue).sum();
            return new DataQualityReport(rawRows, accepted, skipped, cancel, purchase,
                    distinctEntities, distinctProducts, min, max, skipReasons);
        }
    }
}
