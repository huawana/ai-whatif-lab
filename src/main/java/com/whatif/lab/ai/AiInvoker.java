package com.whatif.lab.ai;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.whatif.lab.ai.config.AiProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AI 调用器：给所有模型调用套上<b>有界线程池 + 硬超时 + 异常隔离</b>。
 *
 * <p><b>为什么必须存在</b>：LLM 是「无界延迟」依赖 —— 正常 1~3 秒，限流/故障时挂几十秒。
 * 直接在请求线程里同步调用，卡住的请求会占满 Tomcat 工作线程，
 * 于是<b>整站（包括完全不涉及 AI 的仿真、查询接口）一起不可用</b>。
 * 这和 DashScope 自动配置那个坑是同一类事故，只是触发点不同。
 *
 * <p><b>线程数 4 + 有界队列 32 是刻意的背压设计</b>：
 * AI 是增值功能，不该和主链路抢线程/内存。高峰期 AI 请求排队、超时后被丢弃，
 * 但仿真与查询照常快。<b>宁可 AI 降级，不可主链路变慢</b> —— 这个优先级要在参数里体现出来。
 *
 * <p><b>异常全捕获</b>：超时、鉴权失败、上游报错，一律转成「没拿到结果」的分支，
 * 绝不让异常穿透到 Controller 变成 500 —— 调用方需要能区分
 * 「AI 没开」「AI 挂了」与「平台坏了」。
 */
@Component
public class AiInvoker {

    private static final Logger log = LoggerFactory.getLogger(AiInvoker.class);

    private final ExecutorService executor;
    private final AiProperties properties;

    public AiInvoker(AiProperties properties) {
        this.properties = properties;
        int threads = Math.max(1, properties.threads());
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.queueCapacity())),
                r -> {
                    Thread t = new Thread(r, "whatif-ai-invoke");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 调用结果：文本与错误二选一，调用方据此走确定性降级路径。 */
    public record Result(String text, String error, boolean timeout) {

        public static Result ok(String text) {
            return new Result(text, null, false);
        }

        public static Result fail(String error) {
            return new Result(null, error, false);
        }

        public static Result timedOut(String error) {
            return new Result(null, error, true);
        }

        public boolean isOk() {
            return text != null;
        }
    }

    /** 调用 ReAct Agent（带工具编排）。 */
    public Result invoke(ReactAgent agent, String prompt) {
        return withTimeout(() -> {
            try {
                AssistantMessage message = agent.call(prompt);
                return message == null ? null : message.getText();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, "agent=" + safeName(agent));
    }

    /** 单轮对话调用（Scenario 编译器、结果解释器用；不需要工具编排）。 */
    public Result call(ChatModel model, String systemPrompt, String userPrompt) {
        return withTimeout(() -> {
            var prompt = new Prompt(java.util.List.of(
                    new org.springframework.ai.chat.messages.SystemMessage(systemPrompt),
                    new org.springframework.ai.chat.messages.UserMessage(userPrompt)));
            var response = model.call(prompt);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        }, "chatModel=" + properties.effectiveModel());
    }

    private Result withTimeout(java.util.concurrent.Callable<String> task, String tag) {
        long timeout = properties.timeoutMillis();
        try {
            String text = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, executor).orTimeout(timeout, TimeUnit.MILLISECONDS).join();
            if (text == null || text.isBlank()) {
                return Result.fail("模型返回了空结果");
            }
            return Result.ok(text);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root != root.getCause()) {
                root = root.getCause();
            }
            if (root instanceof TimeoutException || e instanceof TimeoutException) {
                log.warn("AI 调用超时（>{}ms），已放弃并降级：{}", timeout, tag);
                return Result.timedOut("AI 响应超时（超过 " + timeout + "ms），已降级为非 AI 结果");
            }
            log.warn("AI 调用失败，已降级：{} 原因={}", tag, root.toString());
            return Result.fail("AI 调用失败：" + root.getMessage());
        }
    }

    private static String safeName(ReactAgent agent) {
        try {
            return agent.name();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    public int poolSize() {
        return ((ThreadPoolExecutor) executor).getPoolSize();
    }

    public int queueSize() {
        return ((ThreadPoolExecutor) executor).getQueue().size();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        log.info("AI 调用线程池已关闭");
    }
}
