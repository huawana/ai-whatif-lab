package com.whatif.lab.task;

import com.whatif.lab.common.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka 调度器（{@code whatif.dispatch.mode=kafka} 时启用）。
 *
 * <p><b>Kafka 在这里解决的真实问题</b>：一次仿真可能跑几十秒到几分钟。
 * 如果直接在 HTTP 请求线程里算，结果是「用户等一分钟、Tomcat 线程被占住、
 * 请求一多整个服务不可用」。所以必须变成：
 * <pre>
 *   POST /experiments → 建实验记录 → 投递消息 → 立即返回 {experimentId, PENDING}
 *   ...Kafka... → Worker 消费 → 仿真 → 写结果 → SSE 推送完成
 * </pre>
 *
 * <p><b>至少一次语义带来的幂等要求</b>：Kafka 默认是 at-least-once，
 * 同一条消息可能被投递两次（消费者重启、rebalance、ack 丢失）。
 * 所以消费端<b>不能假设消息只来一次</b> —— 幂等判定写在 {@link ExperimentWorker} 里，
 * 这里只负责「取消息 → 交给 worker」。这条边界划清楚了，
 * 换 RabbitMQ / RocketMQ 时幂等逻辑一行都不用重写。
 *
 * <p>没有 broker 时不会启动失败：本类只在 mode=kafka 时被创建，
 * 默认 mode=local 的部署根本不会加载它。
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "whatif.dispatch.mode", havingValue = "kafka")
public class KafkaExperimentDispatcher implements ExperimentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(KafkaExperimentDispatcher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExperimentWorker worker;
    private final JsonCodec json;

    @org.springframework.beans.factory.annotation.Value("${whatif.kafka.topic:whatif-experiment}")
    private String topic;

    public KafkaExperimentDispatcher(KafkaTemplate<String, String> kafkaTemplate,
                                     ExperimentWorker worker,
                                     JsonCodec json) {
        this.kafkaTemplate = kafkaTemplate;
        this.worker = worker;
        this.json = json;
    }

    @Override
    public void dispatch(Long experimentId) {
        // 以 experimentId 作为消息键：同一个实验的消息必然进同一分区 → 分区内有序，
        // 不会出现「重试消息先于首次消息到达」导致的顺序问题
        kafkaTemplate.send(topic, String.valueOf(experimentId), json.write(java.util.Map.of("experimentId", experimentId)))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("实验任务投递 Kafka 失败 experimentId={}", experimentId, ex);
                    } else {
                        log.info("实验任务已投递 experimentId={} partition={} offset={}", experimentId,
                                result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
                    }
                });
    }

    @Override
    public String mode() {
        return "kafka";
    }

    /** 消费端：手工 ack（见 application.properties 的 ack-mode=record），确保失败可重投。 */
    @KafkaListener(topics = "${whatif.kafka.topic:whatif-experiment}", groupId = "${spring.kafka.consumer.group-id:whatif-lab-simulation}")
    public void onMessage(String payload) {
        Long experimentId = json.readMap(payload).get("experimentId") == null
                ? null
                : Long.valueOf(String.valueOf(json.readMap(payload).get("experimentId")));
        if (experimentId == null) {
            log.warn("收到无法解析的任务消息: {}", payload);
            return;
        }
        log.info("Kafka 收到实验任务 experimentId={}", experimentId);
        worker.execute(experimentId);
    }
}
