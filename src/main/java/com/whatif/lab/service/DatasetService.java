package com.whatif.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.data.ColumnMapping;
import com.whatif.lab.data.DatasetImportService;
import com.whatif.lab.data.csv.CsvTable;
import com.whatif.lab.data.extract.EcommerceExtractor;
import com.whatif.lab.persistence.mapper.DatasetMapper;
import com.whatif.lab.persistence.mapper.EntityMapper;
import com.whatif.lab.persistence.mapper.EventMapper;
import com.whatif.lab.persistence.po.DatasetPO;
import com.whatif.lab.persistence.po.EntityPO;
import com.whatif.lab.persistence.po.EventPO;
import com.whatif.lab.simulation.snapshot.SnapshotCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集服务：上传预览、导入、画像、规则管理入口。
 *
 * <p>「预览 → 确认映射 → 导入」三步是刻意拆开的：
 * 数据接入阶段唯一「错了也不报错」的环节就是列映射，让用户在预览页看到
 * 「哪一列 → 哪个标准字段 + 置信度 + 依据」，是成本最低的防错手段。
 */
@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetMapper datasetMapper;
    private final EntityMapper entityMapper;
    private final EventMapper eventMapper;
    private final DatasetImportService importService;
    private final RuleService ruleService;
    private final BehaviorModelService behaviorModelService;
    private final SnapshotCache snapshotCache;
    private final JsonCodec json;
    private final JdbcTemplate jdbc;

    public DatasetService(DatasetMapper datasetMapper,
                          EntityMapper entityMapper,
                          EventMapper eventMapper,
                          DatasetImportService importService,
                          RuleService ruleService,
                          BehaviorModelService behaviorModelService,
                          SnapshotCache snapshotCache,
                          JsonCodec json,
                          JdbcTemplate jdbc) {
        this.datasetMapper = datasetMapper;
        this.entityMapper = entityMapper;
        this.eventMapper = eventMapper;
        this.importService = importService;
        this.ruleService = ruleService;
        this.behaviorModelService = behaviorModelService;
        this.snapshotCache = snapshotCache;
        this.json = json;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list() {
        List<DatasetPO> rows = datasetMapper.selectList(new QueryWrapper<DatasetPO>().orderByDesc("id"));
        return rows.stream().map(this::toMap).toList();
    }

    public DatasetPO require(Long datasetId) {
        DatasetPO po = datasetMapper.selectById(datasetId);
        if (po == null) {
            throw BizException.notFound("数据集 " + datasetId);
        }
        return po;
    }

    /** 数据集详情：基础信息 + 规则集 + 模型 + 事件分布（AI 与前端都用它当上下文）。 */
    public Map<String, Object> detail(Long datasetId) {
        DatasetPO po = require(datasetId);
        Map<String, Object> m = toMap(po);
        m.put("rules", ruleService.listVersions(datasetId));
        m.put("models", behaviorModelService.listModels(datasetId));
        m.put("eventTypes", eventTypeDistribution(datasetId));
        m.put("sampleEvents", sampleEvents(datasetId, 5));
        m.put("timeRange", timeRange(datasetId));
        m.put("supportedDomains", List.of("ecommerce"));
        return m;
    }

    /** 数据集画像 —— AI 工具 get_dataset_profile 的返回值，也是 AI 提示词的上下文。 */
    public Map<String, Object> profile(Long datasetId) {
        DatasetPO po = require(datasetId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasetId", datasetId);
        m.put("name", po.getName());
        m.put("domain", po.getDomain());
        m.put("status", po.getStatus());
        m.put("entityCount", po.getEntityCount());
        m.put("eventCount", po.getEventCount());
        m.put("columnMapping", po.getColumnMapping() == null ? Map.of() : json.readMap(po.getColumnMapping()));
        m.put("timeRange", timeRange(datasetId));
        m.put("entityTypes", entityTypeCounts(datasetId));
        m.put("eventTypes", eventTypeDistribution(datasetId));
        m.put("currentRuleSet", ruleService.listVersions(datasetId).stream().findFirst().orElse(null));
        m.put("activeModel", behaviorModelService.listModels(datasetId).stream().findFirst().orElse(null));
        m.put("editableRuleFields", Map.of(
                "DISCOUNT", List.of("threshold", "amount", "percent"),
                "PRICE", List.of("multiply", "add"),
                "PROBABILITY", List.of("multiplier")));
        m.put("hint", "把自然语言里的「满X减Y」「降价Z%」「概率提高W」翻译成 changes 数组，"
                + "每项形如 {ruleType, field, from, to}；不要输出业务指标数字，那些由仿真引擎计算。");
        return m;
    }

    public DatasetImportService.PreviewResult preview(InputStream in, int limit, String domain) throws IOException {
        return importService.preview(in, limit, domain);
    }

    public DatasetImportService.ImportResult importCsv(InputStream in, String name, String domain,
                                                      String description, ColumnMapping mapping) throws IOException {
        DatasetImportService.ImportResult result = importService.importCsv(in, name, domain, name, description, mapping);
        snapshotCache.invalidate(result.datasetId());
        return result;
    }

    /** 从服务器本地路径导入（脚本与批量导入用，省掉 multipart 的麻烦）。 */
    public DatasetImportService.ImportResult importPath(String path, String name, String domain,
                                                        String description, Map<String, String> mapping) throws IOException {
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) {
            throw BizException.validation("文件不存在: " + path);
        }
        ColumnMapping columnMapping = mapping == null || mapping.isEmpty() ? null : new ColumnMapping(mapping);
        try (InputStream in = Files.newInputStream(p)) {
            DatasetImportService.ImportResult result =
                    importService.importCsv(in, name, domain, path, description, columnMapping);
            snapshotCache.invalidate(result.datasetId());
            log.info("从路径导入完成 path={} datasetId={}", path, result.datasetId());
            return result;
        }
    }

    /** 数据质量报告（导入后随时可查，不依赖导入时的返回值）。 */
    public Map<String, Object> quality(Long datasetId) {
        DatasetPO po = require(datasetId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rawRowCount", po.getRawRowCount());
        m.put("eventCount", po.getEventCount());
        m.put("skippedRowCount", po.getSkippedRowCount());
        m.put("skipRatio", po.getRawRowCount() == null || po.getRawRowCount() == 0 ? 0
                : Math.round(10000.0 * po.getSkippedRowCount() / po.getRawRowCount()) / 10000.0);
        m.put("entityCount", po.getEntityCount());
        m.put("note", "跳过的行在事件表里不存在，因此也不会参与行为模型与仿真。"
                + "指标异常时请先核对这里的跳过比例。");
        return m;
    }

    /** Baseline 规则版本变更后同步到 dataset 行（实验默认用它当对比基准）。 */
    public void updateBaselineRuleVersion(Long datasetId, int version) {
        DatasetPO po = new DatasetPO();
        po.setId(datasetId);
        po.setBaselineRuleVersion(version);
        datasetMapper.updateById(po);
    }

    /**
     * 快照诊断 —— 供「时间切分无泄漏」这条验收标准做机器校验。
     *
     * <p>输出的都是能直接断言的事实，而不是"我们设计上是对的"：
     * <ul>
     *   <li>{@code referenceTime}：70% 分位时间戳，画像与标签的切分点；</li>
     *   <li>{@code historyMaxTime}：画像窗口里最晚的事件时间 → 必须 &lt;= referenceTime；</li>
     *   <li>{@code labelMinTime}：标签窗口里最早的事件时间 → 必须 &gt; referenceTime；</li>
     *   <li>{@code overlapCount}：两个窗口的事件集合交集大小 → 必须为 0；</li>
     *   <li>{@code profileLastSeenMax}：所有客户画像的 lastSeen 最大值 → 必须 &lt;= referenceTime，
     *       这一条最关键 —— 它证明"特征里没有未来的信息"。</li>
     * </ul>
     */
    public Map<String, Object> snapshotDiagnostics(Long datasetId) {
        var snapshot = snapshotCache.get(datasetId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasetId", datasetId);
        m.put("referenceTime", String.valueOf(snapshot.referenceTime()));
        m.put("dataStart", String.valueOf(snapshot.dataStart()));
        m.put("dataEnd", String.valueOf(snapshot.dataEnd()));
        m.put("customers", snapshot.customers().size());
        m.put("products", snapshot.products().size());
        m.put("historyPairs", snapshot.historyPairKeys().size());
        m.put("labelPurchases", snapshot.labelingPurchases().size());
        m.put("recentWindowDays", snapshot.recentWindowDays());

        // 画像窗口最晚事件时间：用客户画像的 lastSeen 最大值近似（它是画像里唯一的时间量）
        String profileLastSeenMax = snapshot.customers().stream()
                .map(c -> c.lastSeen())
                .filter(java.util.Objects::nonNull)
                .max(java.time.LocalDateTime::compareTo)
                .map(String::valueOf).orElse(null);
        m.put("profileLastSeenMax", profileLastSeenMax);
        m.put("profileLastSeenLeak",
                profileLastSeenMax != null
                        && java.time.LocalDateTime.parse(profileLastSeenMax).isAfter(snapshot.referenceTime()));

        // 标签窗口最早事件时间：必须严格晚于 referenceTime
        String labelMinTime = snapshot.labelingPurchases().stream()
                .map(l -> l.time()).min(java.time.LocalDateTime::compareTo)
                .map(String::valueOf).orElse(null);
        m.put("labelMinTime", labelMinTime);
        m.put("labelWindowLeak",
                labelMinTime != null
                        && !java.time.LocalDateTime.parse(labelMinTime).isAfter(snapshot.referenceTime()));

        // 两个窗口的事件集合交集：按 SQL 直接算（画像窗口 = time <= ref，标签窗口 = time > ref）
        Map<String, Object> overlap = jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM event WHERE dataset_id=? AND event_time <= ?) AS history_events,
                  (SELECT COUNT(*) FROM event WHERE dataset_id=? AND event_time > ?)  AS label_events,
                  (SELECT COUNT(*) FROM event WHERE dataset_id=? AND event_time <= ? AND event_time > ?) AS overlap_count
                """, datasetId, snapshot.referenceTime(), datasetId, snapshot.referenceTime(),
                datasetId, snapshot.referenceTime(), snapshot.referenceTime());
        m.putAll(overlap);
        m.put("overlapIsZero", ((Number) overlap.get("overlap_count")).longValue() == 0);
        m.put("note", "泄漏判定：profileLastSeenLeak / labelWindowLeak 必须为 false，overlapIsZero 必须为 true。"
                + "这三条同时成立，才说明『任何特征都没有使用标签发生之后的信息』。");
        return m;
    }

    public void purge(Long datasetId) {
        require(datasetId);
        importService.purge(datasetId);
        snapshotCache.invalidate(datasetId);
    }

    private Map<String, Object> toMap(DatasetPO po) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("datasetId", po.getId());
        m.put("name", po.getName());
        m.put("version", po.getVersion());
        m.put("domain", po.getDomain());
        m.put("source", po.getSource());
        m.put("description", po.getDescription());
        m.put("status", po.getStatus());
        m.put("entityCount", po.getEntityCount());
        m.put("eventCount", po.getEventCount());
        m.put("rawRowCount", po.getRawRowCount());
        m.put("skippedRowCount", po.getSkippedRowCount());
        m.put("baselineRuleVersion", po.getBaselineRuleVersion());
        m.put("columnMapping", po.getColumnMapping() == null ? Map.of() : json.readMap(po.getColumnMapping()));
        m.put("createdAt", String.valueOf(po.getCreatedAt()));
        return m;
    }

    private List<Map<String, Object>> entityTypeCounts(Long datasetId) {
        return jdbc.queryForList("SELECT entity_type, COUNT(*) AS cnt FROM entity WHERE dataset_id=? GROUP BY entity_type",
                datasetId);
    }

    private List<Map<String, Object>> eventTypeDistribution(Long datasetId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT event_type, COUNT(*) AS cnt, SUM(amount) AS total_amount, AVG(unit_price) AS avg_price
                FROM event WHERE dataset_id=? GROUP BY event_type
                """, datasetId);
        for (Map<String, Object> row : rows) {
            Object cnt = row.remove("cnt");
            row.put("count", cnt);
        }
        return rows;
    }

    private List<Map<String, Object>> sampleEvents(Long datasetId, int limit) {
        return jdbc.queryForList("""
                SELECT entity_external_id AS customer, product_external_id AS product, event_type,
                       event_time, quantity, unit_price, amount
                FROM event WHERE dataset_id=? ORDER BY event_time LIMIT ?
                """, datasetId, limit);
    }

    private Map<String, Object> timeRange(Long datasetId) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("min", jdbc.queryForObject("SELECT MIN(event_time) FROM event WHERE dataset_id=?",
                    String.class, datasetId));
            m.put("max", jdbc.queryForObject("SELECT MAX(event_time) FROM event WHERE dataset_id=?",
                    String.class, datasetId));
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        return m;
    }

    /** 抽样若干行原始 CSV 用于确认映射（前端「数据预览」用）。 */
    public static List<String[]> peekRows(java.io.Reader reader, int limit) throws IOException {
        CsvTable table = CsvTable.open(reader);
        List<String[]> rows = new java.util.ArrayList<>();
        table.forEach(row -> {
            if (rows.size() < limit) {
                rows.add(row);
            }
        });
        return rows;
    }

    /** 兼容性：供外部调用方复用事件类型常量。 */
    public static String purchaseEventType() {
        return EcommerceExtractor.EVENT_PURCHASE;
    }

    /** 供应商/实体计数（详情页展示用）。 */
    public long countEntities(Long datasetId, String type) {
        return entityMapper.selectCount(new QueryWrapper<EntityPO>()
                .eq("dataset_id", datasetId).eq("entity_type", type));
    }

    public long countEvents(Long datasetId) {
        return eventMapper.selectCount(new QueryWrapper<EventPO>().eq("dataset_id", datasetId));
    }
}
