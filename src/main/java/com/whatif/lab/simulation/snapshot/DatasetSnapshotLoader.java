package com.whatif.lab.simulation.snapshot;

import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.simulation.SimulationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据集快照加载器：把数据库里的 Entity/Event 压成内存画像。
 *
 * <p>只读数据库，且只读两遍（历史窗口一遍、标签窗口一遍），
 * 无论实验跑 200 次还是 2000 次蒙特卡洛，都只加载一次。
 */
@Component
public class DatasetSnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetSnapshotLoader.class);

    /** 时间切分分位：前 70% 的事件用于画像，后 30% 用于构造训练标签。 */
    public static final double DEFAULT_HISTORY_QUANTILE = 0.70;

    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final SimulationProperties properties;

    public DatasetSnapshotLoader(JdbcTemplate jdbc, JsonCodec json, SimulationProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
    }

    public DatasetSnapshot load(Long datasetId) {
        return load(datasetId, DEFAULT_HISTORY_QUANTILE, 30);
    }

    public DatasetSnapshot load(Long datasetId, double historyQuantile, int recentWindowDays) {
        long start = System.currentTimeMillis();

        Long purchaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event WHERE dataset_id=? AND event_type='PURCHASE'", Long.class, datasetId);
        if (purchaseCount == null || purchaseCount == 0) {
            throw new IllegalStateException("数据集 " + datasetId + " 没有 PURCHASE 事件，无法构建快照");
        }

        LocalDateTime dataStart = jdbc.queryForObject(
                "SELECT MIN(event_time) FROM event WHERE dataset_id=?", LocalDateTime.class, datasetId);
        LocalDateTime dataEnd = jdbc.queryForObject(
                "SELECT MAX(event_time) FROM event WHERE dataset_id=?", LocalDateTime.class, datasetId);

        int offset = (int) Math.floor(purchaseCount * historyQuantile);
        offset = Math.min(offset, purchaseCount.intValue() - 1);
        LocalDateTime referenceTime = jdbc.queryForObject("""
                SELECT event_time FROM event
                WHERE dataset_id=? AND event_type='PURCHASE'
                ORDER BY event_time LIMIT 1 OFFSET ?
                """, LocalDateTime.class, datasetId, offset);

        Map<String, String> customerGroup = loadAttributes(datasetId, "Customer", "group");
        Map<String, String> productDesc = loadAttributes(datasetId, "Product", "description");

        Map<String, CustomerAgg> customerAggs = new LinkedHashMap<>();
        Map<String, ProductAgg> productAggs = new LinkedHashMap<>();
        // 外部主键 → 数组下标。必须用「首次出现即分配」的方式获得下标，
        // 而不是每次去 LinkedHashMap 里做一次 O(n) 的线性找位置 ——
        // 50 万行 × 几千个实体 = 十亿级比较，那会让加载从 1 秒变成几分钟。
        Map<String, Integer> custIdx = new HashMap<>();
        Map<String, Integer> prodIdx = new HashMap<>();
        Map<Long, Integer> pairCounts = new HashMap<>();
        Map<Integer, Double> recentGmv = new HashMap<>();
        LocalDateTime recentFrom = referenceTime.minusDays(recentWindowDays);

        // ---------- 第一遍：历史窗口 → 画像 ----------
        jdbc.query("""
                SELECT entity_external_id, product_external_id, quantity, unit_price, amount, event_time
                FROM event
                WHERE dataset_id=? AND event_type='PURCHASE' AND event_time <= ?
                ORDER BY event_time, id
                """, (RowCallbackHandler) rs -> {
            String cust = rs.getString(1);
            String prod = rs.getString(2);
            int qty = rs.getInt(3);
            double price = rs.getBigDecimal(4).doubleValue();
            double amount = rs.getBigDecimal(5).doubleValue();
            LocalDateTime t = rs.getTimestamp(6).toLocalDateTime();

            int ci = custIdx.computeIfAbsent(cust, k -> {
                customerAggs.put(k, new CustomerAgg());
                return customerAggs.size() - 1;
            });
            int pi = prodIdx.computeIfAbsent(prod, k -> {
                productAggs.put(k, new ProductAgg());
                return productAggs.size() - 1;
            });
            customerAggs.get(cust).add(qty, amount, t);
            productAggs.get(prod).add(qty, price);
            pairCounts.merge(DatasetSnapshot.pairKey(ci, pi), 1, Integer::sum);
            if (t.isAfter(recentFrom)) {
                recentGmv.merge(ci, amount, Double::sum);
            }
        }, datasetId, Timestamp.valueOf(referenceTime));

        // ---------- 第二遍：标签窗口 → 训练正样本 ----------
        List<DatasetSnapshot.PurchaseLabel> labels = new ArrayList<>();
        jdbc.query("""
                SELECT entity_external_id, product_external_id, quantity, unit_price, event_time
                FROM event
                WHERE dataset_id=? AND event_type='PURCHASE' AND event_time > ?
                ORDER BY event_time, id
                """, (RowCallbackHandler) rs -> {
            String cust = rs.getString(1);
            String prod = rs.getString(2);
            int qty = rs.getInt(3);
            double price = rs.getBigDecimal(4).doubleValue();
            LocalDateTime t = rs.getTimestamp(5).toLocalDateTime();
            // 标签窗口里首次出现的客户/商品也要有索引（画像为空 = 新客/新品，是真实存在的状态）
            int ci = custIdx.computeIfAbsent(cust, k -> {
                customerAggs.put(k, new CustomerAgg());
                return customerAggs.size() - 1;
            });
            int pi = prodIdx.computeIfAbsent(prod, k -> {
                productAggs.put(k, new ProductAgg());
                return productAggs.size() - 1;
            });
            labels.add(new DatasetSnapshot.PurchaseLabel(ci, pi, price, qty, t));
        }, datasetId, Timestamp.valueOf(referenceTime));

        // ---------- 组装不可变快照 ----------
        List<CustomerProfile> customers = new ArrayList<>(customerAggs.size());
        List<String> custIds = new ArrayList<>(customerAggs.keySet());
        for (int i = 0; i < custIds.size(); i++) {
            String id = custIds.get(i);
            CustomerAgg a = customerAggs.get(id);
            customers.add(new CustomerProfile(i, id, customerGroup.get(id), a.lines, a.qty, a.spend,
                    a.lines == 0 ? 0 : a.spend / a.lines, a.first, a.last));
        }
        List<ProductProfile> products = new ArrayList<>(productAggs.size());
        List<String> prodIds = new ArrayList<>(productAggs.keySet());
        for (int i = 0; i < prodIds.size(); i++) {
            String id = prodIds.get(i);
            ProductAgg a = productAggs.get(id);
            double avgPrice = a.lines == 0 ? 0 : a.priceSum / a.lines;
            products.add(new ProductProfile(i, id, productDesc.get(id), a.lines, a.qty, avgPrice,
                    a.minPrice, a.maxPrice, a.lines == 0 ? 1 : (int) Math.max(1, Math.round((double) a.qty / a.lines))));
        }

        Map<String, Integer> customerIndex = new HashMap<>();
        for (CustomerProfile c : customers) {
            customerIndex.put(c.externalId(), c.index());
        }
        Map<String, Integer> productIndex = new HashMap<>();
        for (ProductProfile p : products) {
            productIndex.put(p.externalId(), p.index());
        }

        List<Integer> withHistory = customers.stream().filter(c -> c.purchaseLines() > 0).map(CustomerProfile::index).toList();
        log.info("快照加载完成 datasetId={} 客户={}(有历史{}) 商品={} 历史购买对={} 标签样本={} 参考时点={} 耗时={}ms",
                datasetId, customers.size(), withHistory.size(), products.size(), pairCounts.size(),
                labels.size(), referenceTime, System.currentTimeMillis() - start);

        return new DatasetSnapshot(datasetId, referenceTime, dataStart, dataEnd, customers, products,
                pairCounts, labels, customerIndex, productIndex, recentGmv, recentWindowDays);
    }

    // 索引即「首次出现顺序」：两遍扫描都按 (event_time, id) 排序，
    // 因此同样的数据集必然得到同样的索引顺序 → 同样的抽样结果（可复现性的基础）。

    private Map<String, String> loadAttributes(Long datasetId, String entityType, String key) {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT external_id, attributes FROM entity WHERE dataset_id=? AND entity_type=?",
                (RowCallbackHandler) rs -> {
                    Map<String, Object> attrs = json.readMap(rs.getString(2));
                    Object v = attrs.get(key);
                    if (v != null) {
                        out.put(rs.getString(1), String.valueOf(v));
                    }
                }, datasetId, entityType);
        return out;
    }

    private static final class CustomerAgg {
        int lines;
        long qty;
        double spend;
        LocalDateTime first;
        LocalDateTime last;

        void add(int q, double amount, LocalDateTime t) {
            lines++;
            qty += q;
            spend += amount;
            if (first == null || t.isBefore(first)) {
                first = t;
            }
            if (last == null || t.isAfter(last)) {
                last = t;
            }
        }
    }

    private static final class ProductAgg {
        int lines;
        long qty;
        double priceSum;
        double minPrice = Double.MAX_VALUE;
        double maxPrice = 0;

        void add(int q, double price) {
            lines++;
            qty += q;
            priceSum += price;
            minPrice = Math.min(minPrice, price);
            maxPrice = Math.max(maxPrice, price);
        }
    }
}
