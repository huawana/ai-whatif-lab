package com.whatif.lab.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 能力配置（绑定 {@code whatif.ai.*}）。
 *
 * <p>可用性判断收敛到 {@link #isUsable()} 一个方法 —— 装配 ChatModel、端点返回错误、
 * 状态接口展示，三处必须基于同一个判断，否则迟早出现「一处判了总开关、另一处没判」
 * 的不一致，表现为「AI 状态显示可用但调用报错」。
 */
@ConfigurationProperties(prefix = "whatif.ai")
public record AiProperties(
        /** 总开关：线上 AI 出问题（限流/计费/输出失控）时立刻止血，且不需要删 key。 */
        @DefaultValue("true") boolean enabled,
        /**
         * 模型供应商。
         * <ul>
         *   <li>{@code dashscope}（默认）：阿里云百炼，用自带的 DashScope SDK；</li>
         *   <li>{@code deepseek} / {@code openai}：走 OpenAI 兼容协议，base-url 有内置默认值；</li>
         *   <li>其它任意值（如 {@code moonshot} / {@code siliconflow} / {@code ollama}）：
         *       同样走 OpenAI 兼容协议，但必须显式给 {@code base-url}。</li>
         * </ul>
         */
        @DefaultValue("dashscope") String provider,
        /** API Key。空 = AI 不可用，但应用必须照常启动（这是本项目的底线，见 AiModelConfig）。 */
        @DefaultValue("") String apiKey,
        /**
         * OpenAI 兼容端点的 base-url，填**服务根地址**（例如 https://api.deepseek.com、
         * http://127.0.0.1:11434），不要带 {@code /v1} —— spring-ai 会自动补 {@code /v1/chat/completions}。
         */
        @DefaultValue("") String baseUrl,
        /** 模型名：dashscope 下 qwen-plus 是质量/成本/速度的平衡点。 */
        @DefaultValue("qwen-plus") String model,
        /** 单次 Agent 调用超时（毫秒）。必须有值，否则挂起的上游会拖满线程池。 */
        @DefaultValue("60000") long timeoutMillis,
        /** ReAct 循环上限：每多一轮 = 一次真实 token 计费 + 一次上游请求。 */
        @DefaultValue("8") int maxIterations,
        /** AI 专用线程池大小。刻意做小 = 有意为之的背压。 */
        @DefaultValue("4") int threads,
        /** AI 调用排队上限，超过直接拒绝（快速失败，不堆积）。 */
        @DefaultValue("32") int queueCapacity,
        /** 编译器自我纠正的重试次数（DSL 校验失败时把错误回喂给模型）。 */
        @DefaultValue("2") int compileRetries) {

    public boolean isDashScope() {
        return provider == null || provider.isBlank() || "dashscope".equalsIgnoreCase(provider.trim());
    }

    /**
     * 实际使用的 base-url：显式配置优先，其次按供应商给内置默认值。
     *
     * <p>只给 deepseek / openai 内置默认值是刻意的：这两个的官方地址是稳定的公共常识；
     * 其它供应商（月之暗面、硅基流动、公司内网网关、本地 Ollama…）地址千差万别，
     * 与其猜错一个（表现为"请求打到了不存在的地方"，极难排查），不如要求显式配置并给出清晰提示。
     */
    public String effectiveBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.trim();
        }
        String p = provider == null ? "" : provider.trim().toLowerCase();
        // 注意：**不要在这里带上 /v1**。spring-ai 的 OpenAI 客户端会在 base-url 后面自己拼
        // "/v1/chat/completions"，带了就变成 /v1/v1/chat/completions（用本地 mock 实测抓到）。
        return switch (p) {
            case "deepseek" -> "https://api.deepseek.com";
            case "openai" -> "https://api.openai.com";
            default -> "";
        };
    }

    /**
     * 实际使用的模型名。
     *
     * <p>配置里 {@code whatif.ai.model} 的默认值是给 DashScope 用的 {@code qwen-plus}；
     * 换成第三方供应商后如果还留着这个名字，请求会被上游拒绝（"model not found"），
     * 而报错信息通常不会告诉你"是你没改模型名"。所以这里做一次收敛：
     * 只有当模型名**仍是默认值**时才按供应商替换（显式写过的名字一律尊重）。
     */
    public String effectiveModel() {
        String m = model == null ? "" : model.trim();
        boolean looksDefault = m.isEmpty() || "qwen-plus".equalsIgnoreCase(m);
        if (!isDashScope() && looksDefault) {
            String p = provider == null ? "" : provider.trim().toLowerCase();
            return switch (p) {
                case "deepseek" -> "deepseek-chat";
                case "openai" -> "gpt-4o-mini";
                default -> m;
            };
        }
        return m;
    }

    /** 「AI 是否真的可用」的唯一判断入口（装配 ChatModel、接口报错、状态展示三处共用）。 */
    public boolean isUsable() {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return false;
        }
        // 非 DashScope 走 OpenAI 兼容协议：没有 base-url 就是不可用，
        // 不能"先装配再在运行时 404" —— 那正是本项目一直拒绝的故障模式。
        return isDashScope() || !effectiveBaseUrl().isBlank();
    }

    public String unavailableReason() {
        if (!enabled) {
            return "AI 能力已被配置关闭（whatif.ai.enabled=false）";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "未配置模型 API Key（环境变量 WHATIF_AI_API_KEY / AI_API_KEY / AI_DASHSCOPE_API_KEY 任一），"
                    + "AI 功能不可用；手工 Scenario DSL 路径不受影响";
        }
        if (!isDashScope() && effectiveBaseUrl().isBlank()) {
            return "provider=" + provider + " 走 OpenAI 兼容协议，但没给 base-url"
                    + "（环境变量 WHATIF_AI_BASE_URL，填服务根地址如 https://api.moonshot.cn），AI 功能不可用";
        }
        return null;
    }
}
