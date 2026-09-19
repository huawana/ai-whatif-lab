package com.whatif.lab.ai.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatModel 手工装配 —— 「AI 可选、平台必活」这条底线的落点。
 *
 * <p><b>为什么不直接用 DashScope 的自动配置（实测踩过的坑）</b>：
 * 引入 {@code spring-ai-alibaba-starter-dashscope} 后，它的自动配置会<b>无条件</b>创建一批 bean，
 * 其中 {@code DashScopeAgentAutoConfiguration} 在缺少 api-key 时直接抛异常，
 * 而它抛在容器 refresh 阶段 —— 结果不是「AI 功能不可用」，而是
 * <b>整个应用启动失败</b>：
 * <pre>
 * Error creating bean 'dashScopeAgent' ... DashScope API key must be set.
 * → Tomcat 未启动，所有接口 HTTP 000
 * </pre>
 * 一个可选增值功能把整个平台拖死，这是绝对不能接受的故障模式。
 * 所以 application.properties 里把 DashScope 的 10 个自动配置全部
 * {@code spring.autoconfigure.exclude} 掉（列表与 jar 内 AutoConfiguration.imports 逐行核对过），
 * 改由本类手工装配 —— <b>AI 的启停完全由本项目的配置决定</b>，不依赖第三方 starter 的默认行为。
 *
 * <p><b>为什么用 {@code @ConditionalOnExpression} 而不是 {@code @ConditionalOnProperty}</b>：
 * 配置写的是 {@code whatif.ai.api-key=${AI_DASHSCOPE_API_KEY:${ALIQWEN-API:}}}，
 * 属性<b>始终存在</b>（值为空串）。而 {@code @ConditionalOnProperty} 在不指定 havingValue 时
 * 只判断「存在且不为 false」—— 空串会<b>通过</b>判断，于是拿空 key 建客户端，
 * 退化成运行时错误。{@code @ConditionalOnExpression} 能显式表达「非空白」这个语义。
 * {@code trim()} 是有意的：环境变量里带上的空格/换行会让 key「看起来有值、实际签名失败」，
 * 这种问题排查起来非常费时，不如在装配阶段就判掉。
 */
@Configuration
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    /**
     * DashScope（阿里云百炼）路径 —— 默认供应商。
     */
    @Bean
    @ConditionalOnExpression("'dashscope'.equalsIgnoreCase('${whatif.ai.provider:dashscope}') "
            + "and '${whatif.ai.api-key:}'.trim().length() > 0 and ${whatif.ai.enabled:true}")
    public ChatModel whatifDashScopeChatModel(AiProperties properties) {
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(properties.apiKey().trim())
                .build();

        ChatModel model = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .model(properties.effectiveModel())
                        .build())
                .build();

        // 只记模型名，绝不记 key 本身（日志会被转发、归档）
        log.info("AI 已启用：供应商=dashscope ChatModel 装配完成 model={}", properties.effectiveModel());
        return model;
    }

    /**
     * OpenAI 兼容协议路径 —— 一个实现覆盖 DeepSeek / Moonshot(Kimi) / 智谱GLM / 硅基流动 /
     * OpenRouter / 本地 Ollama、vLLM、llama.cpp 等等（它们都提供 {@code POST {baseUrl}/chat/completions}）。
     *
     * <p><b>条件为什么写成这样（两个坑都在条件里判掉）</b>：
     * <ul>
     *   <li>非 dashscope <b>且</b> key 非空；</li>
     *   <li>base-url 要么显式配了，要么是 deepseek/openai（这两个有内置默认地址）——
     *       否则<b>不装配</b>：宁可 AI 不可用（接口返回 1001 并给出人话原因），
     *       也不能装配出一个"每次调用都 404"的客户端。这就是本类开头那条底线的具体执行；</li>
     *   <li>用的是 spring-ai-openai 的<b>核心模块</b>，没有自动配置参与 ——
     *       不重演 DashScope starter 那个"缺 key 就在 refresh 阶段抛异常拖死应用"的事故。</li>
     * </ul>
     */
    @Bean
    @ConditionalOnExpression("!'dashscope'.equalsIgnoreCase('${whatif.ai.provider:dashscope}') "
            + "and '${whatif.ai.api-key:}'.trim().length() > 0 and ${whatif.ai.enabled:true} "
            + "and ('${whatif.ai.base-url:}'.trim().length() > 0 "
            + "     or 'deepseek'.equalsIgnoreCase('${whatif.ai.provider:}') "
            + "     or 'openai'.equalsIgnoreCase('${whatif.ai.provider:}'))")
    public ChatModel whatifOpenAiCompatibleChatModel(AiProperties properties) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.effectiveBaseUrl())
                .apiKey(properties.apiKey().trim())
                .build();

        ChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.effectiveModel())
                        // 温度压到 0.2：这个场景要的是稳定可复现的 DSL，不是发挥文采。
                        // 同一句中文反复编译出不同字段名，会让"可复现"这条承诺失效。
                        .temperature(0.2)
                        .build())
                .build();

        log.info("AI 已启用：供应商={}（OpenAI 兼容）baseUrl={} model={}",
                properties.provider(), properties.effectiveBaseUrl(), properties.effectiveModel());
        return model;
    }
}
