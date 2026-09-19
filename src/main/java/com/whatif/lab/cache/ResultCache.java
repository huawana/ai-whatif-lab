package com.whatif.lab.cache;

import com.whatif.lab.common.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实验结果缓存（Redis）。
 *
 * <p><b>为什么缓存「实验结果」而不是「查询结果」</b>：
 * 普通项目缓存的是热点查询，收益在于省几次 SQL。而本平台最贵的东西是
 * <b>一次蒙特卡洛仿真</b>（几百到几千次模拟、几百万次决策采样，秒级到分钟级 CPU 时间）。
 * 同一套 (数据集 + 模型 + 规则 + 情景 + 种子 + 仿真次数) 的参数组合，
 * 结果在数学上就是唯一的 —— 这正是「可复现」的另一面：可复现 ⇒ 可缓存。
 *
 * <p><b>降级策略（必须有）</b>：Redis 挂了不能让实验跑不了。
 * 所有 Redis 操作都包在 try-catch 里，异常时退化成「未命中」，只是慢，不会错。
 * 这条规则对缓存是成立的（缓存的语义就是「可有可无」），
 * 但<b>绝不能</b>对分布式锁也这样做 —— 锁丢了会导致重复计算，
 * 那属于性能问题，可以接受；而如果用「拿不到锁就直接跳过」来实现，
 * 就会变成正确性问题。两者的处理策略差异是刻意的。
 */
@Component
public class ResultCache {

    private static final Logger log = LoggerFactory.getLogger(ResultCache.class);

    private final StringRedisTemplate redis;
    private final CacheProperties properties;
    private final JsonCodec json;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile boolean redisHealthy = true;

    public ResultCache(StringRedisTemplate redis, CacheProperties properties, JsonCodec json) {
        this.redis = redis;
        this.properties = properties;
        this.json = json;
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            String raw = redis.opsForValue().get(key);
            redisHealthy = true;
            if (raw == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            hits.incrementAndGet();
            return Optional.of(json.read(raw, type));
        } catch (Exception e) {
            errors.incrementAndGet();
            if (redisHealthy) {
                redisHealthy = false;
                log.warn("Redis 缓存读取失败，本次降级为未命中（不影响正确性）: {}", e.getMessage());
            }
            return Optional.empty();
        }
    }

    public void put(String key, Object value) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key, json.write(value), Duration.ofSeconds(properties.resultTtlSeconds()));
            redisHealthy = true;
        } catch (Exception e) {
            errors.incrementAndGet();
            log.warn("Redis 缓存写入失败（忽略，不影响正确性）: {}", e.getMessage());
        }
    }

    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.debug("缓存删除失败: {}", e.getMessage());
        }
    }

    public Stats stats() {
        return new Stats(hits.get(), misses.get(), errors.get(), redisHealthy, properties.enabled());
    }

    public record Stats(long hits, long misses, long errors, boolean redisHealthy, boolean cacheEnabled) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0 : (double) hits / total;
        }
    }
}
