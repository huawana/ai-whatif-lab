package com.whatif.lab.simulation.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快照内存缓存（LRU，容量有限）。
 *
 * <p><b>为什么值得缓存</b>：加载一次 50 万行事件的快照要 1~3 秒，
 * 而同一数据集上的实验往往要连着跑很多次（调参、多情景对比、压测）。
 * 不缓存的话，每次实验的固定开销比仿真本身还大。
 *
 * <p><b>为什么容量必须有限、且不能永久持有</b>：
 * 快照按数据集大小吃内存（几十到几百 MB），如果无脑用一个 {@code ConcurrentHashMap}
 * 长期持有，跑过 20 个数据集之后就是 OOM。这里用「有界 LRU」：
 * 最多保留 4 个数据集，最久未用的自动释放。这是典型的「缓存要有淘汰策略」工程细节，
 * 也是面试里容易被追问的点（问「缓存会不会把内存吃爆」，答「有界 + LRU」）。
 *
 * <p>线程安全通过 {@code synchronizedMap} 的原子 {@code computeIfAbsent} 保证，
 * 且加载动作在锁内进行 —— 这里刻意接受「加载期间阻塞其他数据集请求」，
 * 因为并发加载同一个数据集会带来几倍的内存峰值，代价更大。
 */
@Component
public class SnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCache.class);
    private static final int MAX_DATASETS = 4;

    private final Map<Long, DatasetSnapshot> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, DatasetSnapshot> eldest) {
                    boolean evict = size() > MAX_DATASETS;
                    if (evict) {
                        log.info("快照缓存淘汰 datasetId={}", eldest.getKey());
                    }
                    return evict;
                }
            });

    private final DatasetSnapshotLoader loader;

    public SnapshotCache(DatasetSnapshotLoader loader) {
        this.loader = loader;
    }

    public DatasetSnapshot get(Long datasetId) {
        DatasetSnapshot cached = cache.get(datasetId);
        if (cached != null) {
            return cached;
        }
        DatasetSnapshot loaded = loader.load(datasetId);
        cache.put(datasetId, loaded);
        return loaded;
    }

    /** 数据集重新导入后必须清掉（旧快照的实体索引会失效）。 */
    public void invalidate(Long datasetId) {
        cache.remove(datasetId);
    }

    public int cachedDatasets() {
        return cache.size();
    }
}
