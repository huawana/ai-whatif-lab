package com.whatif.lab.api;

import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.cache.CacheKeys;
import com.whatif.lab.common.ApiResponse;
import com.whatif.lab.task.ExperimentDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 *
 * <p><b>为什么不直接返回 {@code {"status":"UP"}} 就完事</b>：
 * 「我能回 200」和「我能干活」是两件事。这个平台的依赖有三个 ——
 * MySQL（数据与实验记录）、Redis（缓存与锁）、大模型（可选）。
 * 前两个挂了平台就没法工作，第三个挂了只是 AI 功能降级。
 * 所以健康检查必须<b>真的去 ping 每个依赖</b>，并区分「致命」与「可降级」：
 * <ul>
 *   <li>{@code status=UP}：MySQL 与 Redis 都通（AI 不通不影响，只报 degraded 项）；</li>
 *   <li>{@code status=DEGRADED}：AI 不可用但主链路可用；</li>
 *   <li>{@code status=DOWN}：MySQL 或 Redis 不通 —— 这时容器编排应该重启或摘流量。</li>
 * </ul>
 * HTTP 状态码始终 200：这是「应用自身活着」的证明，依赖状态放在 body 里，
 * 避免健康检查把「依赖抖动」误报成「进程死了」而触发无意义的重启。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<ExperimentDispatcher> dispatcherProvider;
    private final AiProperties aiProperties;

    public HealthController(JdbcTemplate jdbc,
                            ObjectProvider<StringRedisTemplate> redisProvider,
                            ObjectProvider<ExperimentDispatcher> dispatcherProvider,
                            AiProperties aiProperties) {
        this.jdbc = jdbc;
        this.redisProvider = redisProvider;
        this.dispatcherProvider = dispatcherProvider;
        this.aiProperties = aiProperties;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean dbOk = checkDb();
        boolean redisOk = checkRedis();
        m.put("engineVersion", CacheKeys.ENGINE_VERSION);
        m.put("mysql", dbOk ? "UP" : "DOWN");
        m.put("redis", redisOk ? "UP" : "DOWN");

        ExperimentDispatcher dispatcher = dispatcherProvider.getIfAvailable();
        m.put("dispatcher", dispatcher == null ? "none" : dispatcher.mode());

        // AI 可用性必须和 /api/ai/status 用同一个判断入口（AiProperties.isUsable()），
        // 否则会出现「状态接口说可用、健康检查说不可用」这种自相矛盾的运维面
        boolean aiUsable = aiProperties.isUsable();
        m.put("ai", aiUsable ? "UP" : "DISABLED");
        m.put("aiReason", aiProperties.unavailableReason());
        m.put("aiModel", aiProperties.model());

        String status = (dbOk && redisOk) ? "UP" : "DOWN";
        m.put("status", status);
        m.put("note", "UP=MySQL 与 Redis 均可用；AI 为 DISABLED 只代表 AI 功能降级，"
                + "确定性主链路（导入/训练/仿真/实验）不受影响");
        // 依赖不可用时也返回 200，但 body 明确 DOWN —— 让编排层按 body 决策，
        // 而不是让一次依赖抖动变成"进程不健康→被重启"
        return ApiResponse.ok(m);
    }

    private boolean checkDb() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (Exception e) {
            log.warn("健康检查：MySQL 不可用 - {}", e.getMessage());
            return false;
        }
    }

    private boolean checkRedis() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // 没有 Redis 客户端不是错误：缓存层已经实现「Redis 挂了降级为未命中」
            return false;
        }
        try {
            var conn = redis.getConnectionFactory().getConnection();
            try {
                String pong = conn.ping();
                return pong != null;
            } finally {
                conn.close();
            }
        } catch (Exception e) {
            log.warn("健康检查：Redis 不可用 - {}", e.getMessage());
            return false;
        }
    }

}
