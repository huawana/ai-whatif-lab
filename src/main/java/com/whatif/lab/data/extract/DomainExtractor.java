package com.whatif.lab.data.extract;

import com.whatif.lab.data.ColumnMapping;
import com.whatif.lab.data.csv.CsvTable;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * 领域适配器 SPI：把「某个行业的原始表」翻译成通用的 Entity/Event。
 *
 * <p><b>跨行业设计的关键落点</b>：仿真引擎、行为模型、指标计算全部只认 Entity/Event，
 * 换行业要写的只有本接口的一个实现。电商与游戏的区别全部收敛在「一张表怎么变成事件」这一步，
 * 而不是散落在引擎里。
 *
 * <p>实现类的契约：
 * <ul>
 *   <li>流式处理（逐行回调），不得把整表读进内存；</li>
 *   <li>脏行必须显式 {@code report.skip(reason)} 计数，不得静默丢弃；</li>
 *   <li>同一个 externalId 的实体属性要能合并（同一客户出现在多行里）。</li>
 * </ul>
 */
public interface DomainExtractor {

    /** 领域标识，与 dataset.domain 对应。 */
    String domain();

    /**
     * 抽取。
     *
     * @param table    已打开表头的 CSV
     * @param mapping  列映射（已确认）
     * @param events   每解析出一行事件就回调一次（调用方负责批量落库）
     * @param report   质量报告累加器
     * @param entities 实体聚合结果（实现类回填：externalId → 属性）
     */
    void extract(CsvTable table,
                 ColumnMapping mapping,
                 Consumer<RawEvent> events,
                 DataQualityReport.Builder report,
                 EntityAccumulator entities) throws IOException;

    /** 实体累加器：抽取阶段只收属性，聚合指标（购买次数/消费额）由统计阶段补齐。 */
    interface EntityAccumulator {
        void entity(String entityType, String externalId, String key, Object value);

        default void entityAll(String entityType, String externalId, java.util.Map<String, Object> attrs) {
            attrs.forEach((k, v) -> entity(entityType, externalId, k, v));
        }
    }

    /** 供实现类复用的小工具。 */
    static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 简单的实体属性容器（线程不安全，抽取是单线程的）。 */
    class SimpleAccumulator implements EntityAccumulator {
        private final java.util.Map<String, java.util.Map<String, Object>> byKey = new LinkedHashMap<>();

        @Override
        public void entity(String entityType, String externalId, String key, Object value) {
            if (value == null) {
                return;
            }
            byKey.computeIfAbsent(entityType + "|" + externalId, k -> new LinkedHashMap<>()).put(key, value);
        }

        public java.util.Map<String, java.util.Map<String, Object>> all() {
            return byKey;
        }

        public List<RawEntity> toRawEntities() {
            List<RawEntity> out = new ArrayList<>(byKey.size());
            byKey.forEach((k, attrs) -> {
                int idx = k.indexOf('|');
                out.add(new RawEntity(k.substring(0, idx), k.substring(idx + 1), attrs));
            });
            return out;
        }
    }

    static LocalDateTime time(String raw) {
        return TimeParser.parse(raw);
    }
}
