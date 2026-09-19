package com.whatif.lab.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 分布式锁（Redis SET NX EX + Lua 释放）。
 *
 * <p><b>三个必须做对的细节</b>（少一个都会在并发下出错）：
 * <ol>
 *   <li><b>加锁必须原子</b>：{@code SET key value NX EX ttl} 是单条命令，
 *       不能用「先 exists 再 set」—— 那中间有窗口，两个线程会同时拿到锁。</li>
 *   <li><b>释放必须校验持有者</b>（Lua 脚本）：{@code if value == mine then del}。
 *       否则会出现经典事故：A 的锁已超时释放，B 拿到锁，A 执行完把 B 的锁删了。</li>
 *   <li><b>必须有 TTL</b>：持有者进程崩溃时锁要能自动释放，否则整个实验流永久阻塞。</li>
 * </ol>
 *
 * <p><b>与缓存的降级策略对比（面试常问）</b>：Redis 不可用时，
 * 锁直接返回「获取失败」而不是「假装拿到」。因为这里的语义是「互斥」，
 * 假装拿到就等于放弃了互斥保证。调用方的正确反应是「走非互斥路径」，
 * 由调用方决定是否可以接受（实验结果仍然正确，只是可能重复计算）。
 */
@Component
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    /** 释放锁：值必须匹配才删除。KEYS[1]=锁键，ARGV[1]=持有者标识。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public DistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 锁句柄（持有者标识 + 键），用于安全释放。 */
    public record Handle(String key, String token, boolean acquired) {
        public boolean isAcquired() {
            return acquired;
        }
    }

    public Handle tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
            return new Handle(key, token, Boolean.TRUE.equals(ok));
        } catch (Exception e) {
            log.warn("获取分布式锁失败（Redis 不可用），按未获取处理: {}", e.getMessage());
            return new Handle(key, token, false);
        }
    }

    public void unlock(Handle handle) {
        if (handle == null || !handle.acquired()) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, Collections.singletonList(handle.key()), handle.token());
        } catch (Exception e) {
            log.debug("释放分布式锁失败（将由 TTL 自动过期）: {}", e.getMessage());
        }
    }
}
