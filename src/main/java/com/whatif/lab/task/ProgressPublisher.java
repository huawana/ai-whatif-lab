package com.whatif.lab.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实验进度推送（SSE）。
 *
 * <p><b>为什么用 SSE 而不是轮询/WebSocket</b>：进度是典型的<b>服务端单向推送</b>场景，
 * SSE 就是为它设计的：一个普通 HTTP 连接、天然支持断线重连、不需要额外协议升级，
 * 前端一个 {@code EventSource} 就完事。WebSocket 双向能力在这里是浪费；
 * 轮询则要么浪费请求、要么延迟明显。
 *
 * <p>两个实现细节：
 * <ol>
 *   <li><b>晚订阅者要能立刻看到当前进度</b>：进度快照存在内存里，
 *       新连接建立时先把快照推一次 —— 否则用户打开页面可能先看到几秒空白。</li>
 *   <li><b>推送失败必须吞掉异常并摘除连接</b>：客户端关页面是常态，
 *       一次推送失败不能让 worker 线程崩掉（那是把 UI 问题升级成计算问题）。</li>
 * </ol>
 */
@Component
public class ProgressPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProgressPublisher.class);

    private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> snapshot = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long experimentId) {
        // 超时设长：一次仿真可能跑几十秒到几分钟
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        subscribers.computeIfAbsent(experimentId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(experimentId, emitter));
        emitter.onTimeout(() -> remove(experimentId, emitter));
        emitter.onError(e -> remove(experimentId, emitter));

        Map<String, Object> current = snapshot.get(experimentId);
        if (current != null) {
            send(emitter, experimentId, current);
        }
        return emitter;
    }

    public void publish(Long experimentId, String status, int percent, String stage) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("experimentId", experimentId);
        event.put("status", status);
        event.put("progress", percent);
        event.put("stage", stage);
        event.put("timestamp", java.time.LocalDateTime.now().toString());
        snapshot.put(experimentId, event);
        List<SseEmitter> list = subscribers.get(experimentId);
        if (list == null) {
            return;   // 没人订阅（异步任务很常见），只更新快照即可
        }
        for (SseEmitter emitter : new ArrayList<>(list)) {
            send(emitter, experimentId, event);
        }
    }

    public void complete(Long experimentId, Object payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("experimentId", experimentId);
        event.put("status", "SUCCESS");
        event.put("progress", 100);
        event.put("stage", "完成");
        event.put("result", payload);
        snapshot.put(experimentId, event);
        List<SseEmitter> list = subscribers.remove(experimentId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            send(emitter, experimentId, event);
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开，忽略
            }
        }
    }

    private void send(SseEmitter emitter, Long experimentId, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(event));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 推送失败，摘除该连接 experimentId={} cause={}", experimentId, e.getMessage());
            remove(experimentId, emitter);
        }
    }

    private void remove(Long experimentId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(experimentId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    public Map<String, Object> snapshotOf(Long experimentId) {
        return snapshot.getOrDefault(experimentId, Map.of());
    }

    public int subscriberCount(Long experimentId) {
        List<SseEmitter> list = subscribers.get(experimentId);
        return list == null ? 0 : list.size();
    }
}
