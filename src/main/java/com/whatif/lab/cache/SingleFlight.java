package com.whatif.lab.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * SingleFlight：把「同时到达的相同请求」合并成一次执行，其余共享结果。
 *
 * <p><b>为什么这个平台尤其需要它</b>：本平台的调用方是<b>人和 AI Agent</b>。
 * Agent 在一次对话里可能连续触发几次「跑同一个实验」的工具调用（重试、追问、换个说法再问），
 * 用户也可能狂点「运行」。而一次仿真是秒级到分钟级的 CPU 密集型任务 ——
 * 100 个相同请求各算一遍，等于把 CPU 白白烧掉 100 倍。
 *
 * <p><b>实现：不是「锁住等别人算完」，而是「抢到锁的人算，没抢到的人等缓存」</b>。
 * 流程：
 * <pre>
 *   1) 查缓存 → 命中直接返回（最快路径，绝大多数请求走这里）
 *   2) 抢锁成功 → 执行 loader，写缓存，返回
 *   3) 抢锁失败 → 说明别人正在算，短暂轮询缓存（rebuild-wait-millis），拿到就共享
 *   4) 轮询超时 → 退化为自己算（宁可多算一次，也不让用户为一个锁干等）
 * </pre>
 * 第 4 步是关键取舍：SingleFlight 的目标是「把 N 次压成接近 1 次」，
 * 而不是「保证绝不重复」。为了 100% 去重而让请求阻塞几秒，是典型的过度设计。
 *
 * <p>另外还有一个进程内的一级合并（{@code inFlight}）：同一 JVM 内的并发请求
 * 连 Redis 往返都省掉。多实例部署时进程内合并失效，由 Redis 那一层兜住。
 */
@Component
public class SingleFlight {

    private static final Logger log = LoggerFactory.getLogger(SingleFlight.class);

    private final ResultCache cache;
    private final DistributedLock lock;
    private final CacheProperties properties;
    private final java.util.concurrent.ConcurrentHashMap<String, Object> inFlightMonitors =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SingleFlight(ResultCache cache, DistributedLock lock, CacheProperties properties) {
        this.cache = cache;
        this.lock = lock;
        this.properties = properties;
    }

    public <T> Optional<T> get(String cacheKey, Class<T> type) {
        return cache.get(cacheKey, type);
    }

    /**
     * 带单飞的计算。
     *
     * @param cacheKey   缓存键
     * @param lockKey    锁键（通常与缓存键同源）
     * @param type       结果类型
     * @param loader     真正执行计算的逻辑
     */
    public <T> LoadResult<T> execute(String cacheKey, String lockKey, Class<T> type, Supplier<T> loader) {
        // 1) 缓存命中
        Optional<T> cached = cache.get(cacheKey, type);
        if (cached.isPresent()) {
            return new LoadResult<>(cached.get(), true, false, 0);
        }
        if (!properties.enabled()) {
            return new LoadResult<>(loader.get(), false, false, 0);
        }

        // 2) 进程内合并：同一 JVM 内相同键的并发请求串行化（省掉 Redis 往返）
        Object monitor = inFlightMonitors.computeIfAbsent(cacheKey, k -> new Object());
        try {
            synchronized (monitor) {
                Optional<T> second = cache.get(cacheKey, type);
                if (second.isPresent()) {
                    return new LoadResult<>(second.get(), true, false, 0);
                }

                // 3) 抢分布式锁
                DistributedLock.Handle handle = lock.tryLock(lockKey,
                        Duration.ofSeconds(properties.lockTtlSeconds()));
                if (!handle.isAcquired()) {
                    Optional<T> waited = waitForRebuild(cacheKey, type);
                    if (waited.isPresent()) {
                        log.info("SingleFlight：共享他人正在执行/刚完成的结果 key={}", cacheKey);
                        return new LoadResult<>(waited.get(), true, true, properties.rebuildWaitMillis());
                    }
                    log.info("SingleFlight：等待未命中，退化为自行计算 key={}", cacheKey);
                }

                long start = System.currentTimeMillis();
                try {
                    T value = loader.get();
                    cache.put(cacheKey, value);
                    return new LoadResult<>(value, false, false, System.currentTimeMillis() - start);
                } finally {
                    lock.unlock(handle);
                }
            }
        } finally {
            inFlightMonitors.remove(cacheKey, monitor);
        }
    }

    private <T> Optional<T> waitForRebuild(String cacheKey, Class<T> type) {
        long deadline = System.currentTimeMillis() + properties.rebuildWaitMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            Optional<T> value = cache.get(cacheKey, type);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    /**
     * @param sharedWithOthers 是否共享了别人的计算结果（SingleFlight 生效的标志）
     * @param elapsedMs        本次执行的耗时（共享时为等待耗时）
     */
    public record LoadResult<T>(T value, boolean cacheHit, boolean sharedWithOthers, long elapsedMs) {
    }
}
