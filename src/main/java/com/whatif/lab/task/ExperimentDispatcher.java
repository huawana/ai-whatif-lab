package com.whatif.lab.task;

/**
 * 实验任务调度 SPI —— 「谁来触发执行」这一层的可替换点。
 *
 * <p>为什么把它抽成接口而不是直接写死线程池：
 * 本地开发/单机部署用进程内线程池最省事（无外部依赖，开箱即跑）；
 * 要演示「异步任务 + 消息队列 + 消费幂等 + 失败重试」这套工程能力时，
 * 换成 Kafka 只改一个配置项，<b>业务代码零改动</b> ——
 * 因为状态机、幂等判定、重试计数全都写在 {@link ExperimentWorker} 里，
 * 传输层只负责「把 experimentId 送到 worker 手上」。
 *
 * <p>这也是我对「该不该上微服务/消息队列」的态度：<b>能力要做出来，但依赖要是可选的</b>。
 * 一个必须先把 Kafka 拉起来才能启动的项目，在别人机器上大概率跑不起来，
 * 那反而证明了工程能力的短板。
 */
public interface ExperimentDispatcher {

    /** 投递一个实验任务（异步）。 */
    void dispatch(Long experimentId);

    /** 当前模式（local / kafka），用于状态接口展示。 */
    String mode();

    // 【为什么没有 @Configuration + @Bean 工厂方法】见 LocalExperimentDispatcher 的类注释：
    // 两个实现类都实现本接口，装配必须由实现类上的 @ConditionalOnProperty 决定，
    // 否则会同时出现「bean 歧义」或「找不到具体实现类 bean」两种失败之一。
}
