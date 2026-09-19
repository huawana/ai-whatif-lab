package com.whatif.lab.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 任务调度参数。
 *
 * @param mode          {@code local} 进程内线程池 / {@code kafka} 消息队列
 * @param threads       本地执行线程数
 * @param timeoutSeconds 单个实验最长执行时间，超时判失败（防止一个病态实验占死 worker）
 * @param maxRetry      失败重试上限
 */
@ConfigurationProperties(prefix = "whatif.dispatch")
public record DispatchProperties(
        @DefaultValue("local") String mode,
        @DefaultValue("2") int threads,
        @DefaultValue("300") int timeoutSeconds,
        @DefaultValue("3") int maxRetry) {
}
