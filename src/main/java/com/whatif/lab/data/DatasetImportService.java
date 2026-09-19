package com.whatif.lab.data;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.data.csv.CsvTable;
import com.whatif.lab.data.extract.DataQualityReport;
import com.whatif.lab.data.extract.DomainExtractor;
import com.whatif.lab.data.extract.DomainExtractorRegistry;
import com.whatif.lab.data.extract.EcommerceExtractor;
import com.whatif.lab.data.extract.RawEntity;
import com.whatif.lab.data.extract.RawEvent;
import com.whatif.lab.persistence.mapper.DatasetMapper;
import com.whatif.lab.persistence.mapper.EntityMapper;
import com.whatif.lab.persistence.mapper.EventMapper;
import com.whatif.lab.persistence.po.DatasetPO;
import com.whatif.lab.persistence.po.EntityPO;
import com.whatif.lab.service.RuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集导入：原始 CSV → Entity/Event → MySQL。
 *
 * <p><b>三个关键工程决策</b>：
 * <ol>
 *   <li><b>流式，不落地中间文件</b>：边解析边批量入库，内存占用与文件大小无关。
 *       50 万行 CSV 的峰值堆占用只在「一批 2000 行」的量级。</li>
 *   <li><b>不用一个大事务包住整个导入</b>：导入要跑几十秒，一个事务意味着
 *       巨大的 undo log、长时间持有的锁、以及失败后的全量回滚重来。
 *       这里改为「数据集行先落库并置 IMPORTING → 分批自动提交 → 最后置 READY」，
 *       失败时数据集保持 FAILED 状态并记录原因，可以只重导而不用管半成品。</li>
 *   <li><b>导入结果必须带数据质量报告</b>：跳过了多少行、为什么跳过，
 *       全部显式返回。指标不对劲时第一件事就是看这份报告，
 *       而不是猜「是策略失效还是数据少了 3%」。</li>
 * </ol>
 */
@Service
public class DatasetImportService {

    private static final Logger log = LoggerFactory.getLogger(DatasetImportService.class);

    private final DatasetMapper datasetMapper;
    private final EntityMapper entityMapper;
    private final EventMapper eventMapper;
    private final BulkInserter bulkInserter;
    private final DomainExtractorRegistry extractors;
    private final RuleService ruleService;
    private final JsonCodec json;
    private final ImportProperties properties;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DatasetImportService(DatasetMapper datasetMapper,
                                EntityMapper entityMapper,
                                EventMapper eventMapper,
                                BulkInserter bulkInserter,
                                DomainExtractorRegistry extractors,
                                RuleService ruleService,
                                JsonCodec json,
                                ImportProperties properties,
                                org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.datasetMapper = datasetMapper;
        this.entityMapper = entityMapper;
        this.eventMapper = eventMapper;
        this.bulkInserter = bulkInserter;
        this.extractors = extractors;
        this.ruleService = ruleService;
        this.json = json;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** 导入结果。 */
    public record ImportResult(Long datasetId,
                               String datasetName,
                               int datasetVersion,
                               int baselineRuleVersion,
                               ColumnMapping mapping,
                               Map<String, String> mappingConfidence,
                               DataQualityReport report,
                               int entityCount,
                               int eventCount,
                               long elapsedMs) {
    }

    /** 预览结果（上传前的字段映射确认页用）。 */
    public record PreviewResult(List<String> header,
                                List<List<String>> sampleRows,
                                ColumnMapping suggestedMapping,
                                Map<String, String> confidence,
                                Map<String, String> evidence,
                                List<String> missingRequired,
                                List<String> supportedDomains,
                                int estimatedColumns) {
    }

    // ------------------------------------------------------------------ 预览

    /**
     * 只读前若干行做字段映射建议。
     *
     * <p>为什么要先预览再导入：列映射是数据接入里唯一「错了也不报错」的环节。
     * 让用户在映射确认页上看到「哪一列 → 哪个标准字段 + 置信度 + 依据」，
     * 是成本最低的防错手段（也正好是 AI 数据映射助手的落点）。
     */
    public PreviewResult preview(InputStream in, int limit, String domain) throws IOException {
        MappingSuggester.Suggestion suggestion;
        List<String> header;
        List<List<String>> sample = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            CsvTable table = CsvTable.open(reader);
            header = table.header();
            suggestion = MappingSuggester.suggest(header);
            int[] taken = {0};
            table.forEach(row -> {
                if (taken[0] >= limit) {
                    return;
                }
                List<String> line = new ArrayList<>(row.length);
                for (String cell : row) {
                    line.add(cell);
                }
                sample.add(line);
                taken[0]++;
            });
        }
        return new PreviewResult(header, sample, suggestion.mapping(), suggestion.confidence(),
                suggestion.evidence(), suggestion.mapping().missingRequired(),
                extractors.domains(), header.size());
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 导入 CSV。
     *
     * @param in          CSV 流（UTF-8）
     * @param name        数据集名（同名会自增 version）
     * @param domain      领域（ecommerce / 未来 game / enterprise）
     * @param sourceName  来源标识（文件名或 URL），写进 dataset.source 便于追溯
     * @param mapping     用户确认后的列映射；null = 用启发式建议
     */
    public ImportResult importCsv(InputStream in,
                                  String name,
                                  String domain,
                                  String sourceName,
                                  String description,
                                  ColumnMapping mapping) throws IOException {

        DomainExtractor extractor = extractors.require(domain == null ? "ecommerce" : domain);
        long start = System.currentTimeMillis();

        Long datasetId = createDatasetRow(name, domain, sourceName, description);
        log.info("开始导入 datasetId={} name={} domain={}", datasetId, name, domain);

        DataQualityReport.Builder report = DataQualityReport.builder();
        DomainExtractor.SimpleAccumulator entityAcc = new DomainExtractor.SimpleAccumulator();

        Map<String, CustomerAgg> customerAggs = new LinkedHashMap<>();
        Map<String, ProductAgg> productAggs = new LinkedHashMap<>();
        List<Object[]> eventBatch = new ArrayList<>(properties.batchSize());
        int[] written = {0};
        // effective 要在 try 之外声明：它在 try 里赋值、在 try 之后还要用于落库与返回，
        // 放在 try 的局部作用域里会编译不过（而且这种作用域错误很值得记住）
        ColumnMapping effective;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            CsvTable table = CsvTable.open(reader);
            effective = mapping != null ? mapping : MappingSuggester.suggest(table.header()).mapping();
            List<String> missing = effective.missingRequired();
            if (!missing.isEmpty()) {
                throw BizException.validation("列映射缺少必填字段: " + missing + "。请在前端确认列映射后重试。");
            }
            extractor.extract(table, effective, event -> {
                eventBatch.add(toEventRow(event));
                written[0]++;
                if (eventBatch.size() >= properties.batchSize()) {
                    flushEvents(datasetId, eventBatch);
                }
                // 购买事件才参与行为画像；取消事件保留在事件表但不进画像
                if (EcommerceExtractor.EVENT_PURCHASE.equals(event.eventType())) {
                    accumulate(customerAggs, productAggs, event);
                }
            }, report, entityAcc);
        } catch (RuntimeException e) {
            markFailed(datasetId, e.getMessage());
            throw e;
        }
        flushEvents(datasetId, eventBatch);

        // 实体的最终属性 = 抽取阶段收到的 + 统计阶段算出来的画像
        writeEntities(datasetId, entityAcc.toRawEntities(), customerAggs, productAggs);

        // 权威计数：直接从数据库 COUNT(*)，不信批量插入的返回值（见 BulkInserter 的注释）。
        // 这一步很便宜（一次 count），但它把「入库到底成功了多少行」变成一个不依赖驱动的硬事实 ——
        // 数据导入这类一次性、不可回滚的操作，宁可多查一次库也不能让统计数字说谎。
        long entityCount = countRows("entity", datasetId);
        long eventCount = countRows("event", datasetId);

        ruleService.createDefaultBaseline(datasetId);
        int ruleVersion = ruleService.currentVersion(datasetId, null);

        DataQualityReport quality = report.build(customerAggs.size(), productAggs.size());
        updateDatasetReady(datasetId, quality, (int) entityCount, (int) eventCount, effective, ruleVersion);

        long elapsed = System.currentTimeMillis() - start;
        log.info("导入完成 datasetId={} 事件={}(抽取{}) 实体={} 耗时={}ms 跳过={}",
                datasetId, eventCount, written[0], entityCount, elapsed, quality.skippedRows());
        if (eventCount != written[0]) {
            // 抽取数与入库数不一致：说明有行没写进去，这属于必须暴露的异常（而不是悄悄继续）
            log.error("事件数不一致：抽取={} 入库={}，请检查写入批次是否被丢弃", written[0], eventCount);
        }
        return new ImportResult(datasetId, name, currentVersion(name), ruleVersion, effective,
                Map.of(), quality, (int) entityCount, (int) eventCount, elapsed);
    }

    private long countRows(String table, Long datasetId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE dataset_id = ?", Long.class, datasetId);
        return count == null ? 0 : count;
    }

    private static Object[] toEventRow(RawEvent e) {
        return new Object[]{
                e.entityExternalId(),
                e.eventType(),
                e.productExternalId(),
                Timestamp.valueOf(e.eventTime()),
                e.quantity(),
                e.unitPrice(),
                e.amount(),
                null   // metadata 留给未来扩展（渠道/设备/活动 id）
        };
    }

    private void flushEvents(Long datasetId, List<Object[]> batch) {
        if (batch.isEmpty()) {
            return;
        }
        bulkInserter.insertEvents(datasetId, batch, properties.batchSize());
        batch.clear();
    }

    // ------------------------------------------------------------------ 画像聚合

    private static final class CustomerAgg {
        int lines;
        long qty;
        BigDecimal spend = BigDecimal.ZERO;
        LocalDateTime first;
        LocalDateTime last;

        void add(RawEvent e) {
            lines++;
            qty += e.quantity();
            spend = spend.add(e.amount());
            if (first == null || e.eventTime().isBefore(first)) {
                first = e.eventTime();
            }
            if (last == null || e.eventTime().isAfter(last)) {
                last = e.eventTime();
            }
        }
    }

    private static final class ProductAgg {
        int lines;
        long qty;
        BigDecimal priceSum = BigDecimal.ZERO;
        BigDecimal lastPrice;
        String description;

        void add(RawEvent e) {
            lines++;
            qty += e.quantity();
            priceSum = priceSum.add(e.unitPrice());
            lastPrice = e.unitPrice();
        }
    }

    private static void accumulate(Map<String, CustomerAgg> customers, Map<String, ProductAgg> products, RawEvent e) {
        customers.computeIfAbsent(e.entityExternalId(), k -> new CustomerAgg()).add(e);
        products.computeIfAbsent(e.productExternalId(), k -> new ProductAgg()).add(e);
    }

    private void writeEntities(Long datasetId,
                              List<RawEntity> rawEntities,
                              Map<String, CustomerAgg> customers,
                              Map<String, ProductAgg> products) {
        List<Object[]> rows = new ArrayList<>(rawEntities.size());
        for (RawEntity e : rawEntities) {
            Map<String, Object> attrs = new LinkedHashMap<>(e.attributes());
            if (EcommerceExtractor.CUSTOMER.equals(e.entityType())) {
                CustomerAgg agg = customers.get(e.externalId());
                if (agg != null) {
                    attrs.put("purchaseLines", agg.lines);
                    attrs.put("totalQuantity", agg.qty);
                    attrs.put("totalSpend", round(agg.spend));
                    attrs.put("avgLineAmount", round(agg.spend.divide(BigDecimal.valueOf(Math.max(agg.lines, 1)), 4, RoundingMode.HALF_UP)));
                    attrs.put("firstSeen", String.valueOf(agg.first));
                    attrs.put("lastSeen", String.valueOf(agg.last));
                }
            } else if (EcommerceExtractor.PRODUCT.equals(e.entityType())) {
                ProductAgg agg = products.get(e.externalId());
                if (agg != null) {
                    attrs.put("purchaseLines", agg.lines);
                    attrs.put("totalQuantity", agg.qty);
                    attrs.put("avgUnitPrice", round(agg.priceSum.divide(BigDecimal.valueOf(Math.max(agg.lines, 1)), 4, RoundingMode.HALF_UP)));
                }
            }
            rows.add(new Object[]{e.entityType(), e.externalId(), json.write(attrs)});
        }
        bulkInserter.insertEntities(datasetId, rows, properties.batchSize());
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ dataset 行

    private Long createDatasetRow(String name, String domain, String source, String description) {
        DatasetPO po = new DatasetPO();
        po.setName(name);
        po.setDomain(domain == null ? "ecommerce" : domain);
        po.setSource(source);
        po.setVersion(currentVersion(name));
        po.setDescription(description);
        po.setStatus("IMPORTING");
        po.setEntityCount(0);
        po.setEventCount(0);
        po.setRawRowCount(0);
        po.setSkippedRowCount(0);
        datasetMapper.insert(po);
        return po.getId();
    }

    private int currentVersion(String name) {
        DatasetPO last = datasetMapper.selectOne(new QueryWrapper<DatasetPO>()
                .eq("name", name).orderByDesc("version").last("limit 1"));
        return last == null ? 1 : last.getVersion() + 1;
    }

    private void markFailed(Long datasetId, String message) {
        DatasetPO po = new DatasetPO();
        po.setId(datasetId);
        po.setStatus("FAILED");
        po.setDescription("导入失败: " + message);
        datasetMapper.updateById(po);
        log.error("导入失败 datasetId={} reason={}", datasetId, message);
    }

    private void updateDatasetReady(Long datasetId, DataQualityReport quality, int entityCount, int eventCount,
                                    ColumnMapping mapping, int ruleVersion) {
        DatasetPO po = new DatasetPO();
        po.setId(datasetId);
        po.setStatus("READY");
        po.setEntityCount(entityCount);
        po.setEventCount(eventCount);
        po.setRawRowCount(quality.rawRows());
        po.setSkippedRowCount(quality.skippedRows());
        po.setColumnMapping(json.write(mapping.fields()));
        po.setBaselineRuleVersion(ruleVersion);
        datasetMapper.updateById(po);
    }

    /** 清空一个数据集的实体/事件（重导前用）。 */
    public void purge(Long datasetId) {
        eventMapper.delete(new QueryWrapper<com.whatif.lab.persistence.po.EventPO>().eq("dataset_id", datasetId));
        entityMapper.delete(new QueryWrapper<EntityPO>().eq("dataset_id", datasetId));
        log.info("已清空 datasetId={} 的实体与事件", datasetId);
    }
}
