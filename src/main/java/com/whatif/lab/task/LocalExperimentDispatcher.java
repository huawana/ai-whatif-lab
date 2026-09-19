package com.whatif.lab.task;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内线程池调度器。
 *
 * <p><b>线程数刻意做小（默认 2）</b>：仿真是 CPU 密集型任务，
 * 线程数开成「核数 × 2」只会让多个仿真互相抢 CPU、整体更慢；
 * 更重要的是，实验任务绝不该把 Tomcat 的请求线程吃满 ——
 * 用独立的小线程池 + 有界队列，队列满时直接拒绝（快速失败），
 * 保证「实验排队」永远不会演变成「整个平台不可用」。
 *
 * <p>队列用<b>有界</b> {@link LinkedBlockingQueue}：无界队列在请求洪峰时
 * 会把内存吃光（任务对象堆积），有界队列 + 拒绝策略才是可控的。
 */
@org.springframework.stereotype.Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "whatif.dispatch.mode", havingValue = "local", matchIfMissing = true)
public class LocalExperimentDispatcher implements ExperimentDispatcher {
    // 【实测踩到的坑】两个实现类都实现同一个接口，必须保证「同一时刻容器里只有一个该类型的 bean」。
    // 踩过的两种错法：
    //   ① 实现类都加 @Component，再用 @Bean 方法按条件再注册一次 → 容器里 3 个同类型 bean
    //      → NoUniqueBeanDefinitionException（expected single matching bean but found 3）；
    //   ② 实现类都不注册、只由 @Bean 方法注册 → 但 @Bean 方法的参数要求注入具体实现类时
    //      → Consider defining a bean of type 'LocalExperimentDispatcher'。
    // 正解：条件注解直接打在实现类上（下面这行 + Kafka 那份的 havingValue），
    // 让 Spring 自己决定装配哪一个，既没有歧义也不需要工厂方法。

    private static final Logger log = LoggerFactory.getLogger(LocalExperimentDispatcher.class);

    private final ExperimentWorker worker;
    private final ThreadPoolExecutor executor;

    public LocalExperimentDispatcher(ExperimentWorker worker, DispatchProperties properties) {
        this.worker = worker;
        int threads = Math.max(1, properties.threads());
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "whatif-simulation-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(64), factory, new ThreadPoolExecutor.AbortPolicy());
        log.info("本地调度器已启用：线程数={} 队列上限=64", threads);
    }

    @Override
    public void dispatch(Long experimentId) {
        executor.execute(() -> {
            try {
                worker.execute(experimentId);
            } catch (Throwable t) {
                // worker 内部已处理失败状态；这里只兜底，绝不让线程池静默吞掉异常
                log.error("实验任务执行异常 experimentId={}", experimentId, t);
            }
        });
    }

    @Override
    public String mode() {
        return "local";
    }

    /** 队列水位（状态接口展示用）。 */
    public int queueSize() {
        return executor.getQueue().size();
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService es = executor;
        es.shutdown();
        try {
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                es.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            es.shutdownNow();
        }
    }
}
