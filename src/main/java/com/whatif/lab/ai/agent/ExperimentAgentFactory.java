package com.whatif.lab.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.ai.tool.ExperimentTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Agent 装配中心（Spring AI Alibaba ReactAgent）。
 *
 * <p>两个 Agent 共用一个工具箱，差别只在系统提示词与「被允许做什么」：
 * <ul>
 *   <li><b>whatif_analyst</b>（全权）：可以跑实验，会真的消耗 CPU 与 token，用于演示 Agent 自主编排；</li>
 *   <li><b>whatif_advisor</b>（只读）：只能看数据、规则、指标定义、历史结果，不跑实验。
 *       用于「先聊清楚再决定要不要算」的场景，成本低、也不会被一句话触发昂贵计算。</li>
 * </ul>
 * 这个区分是刻意的：<b>让模型拥有「可花钱的动作」时，必须给它一个明确的、受约束的入口</b>，
 * 否则一次误解就可能触发几十次仿真。
 *
 * <p><b>为什么用 {@link ObjectProvider} 注入 ChatModel</b>：
 * 没配 key 时容器里<b>不存在</b> ChatModel bean（见 {@code AiModelConfig}），
 * 构造器直接注入会「启动即失败」。用 ObjectProvider 就变成「可以没有」，
 * 拿不到时所有 agent 方法返回 {@code Optional.empty()}，调用方走确定性降级路径。
 * 这就是「AI 是加分项、不是必需品」在代码上的落点。
 */
@Component
public class ExperimentAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(ExperimentAgentFactory.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AiProperties properties;
    private final ExperimentTools tools;
    private volatile boolean loggedUnavailable;

    public ExperimentAgentFactory(ObjectProvider<ChatModel> chatModelProvider,
                                  AiProperties properties,
                                  ExperimentTools tools) {
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
        this.tools = tools;
    }

    public boolean available() {
        if (!properties.isUsable()) {
            if (!loggedUnavailable) {
                log.info("AI 不可用：{}", properties.unavailableReason());
                loggedUnavailable = true;
            }
            return false;
        }
        return chatModelProvider.getIfAvailable() != null;
    }

    public String unavailableReason() {
        return properties.unavailableReason() == null
                ? "ChatModel 未装配（依赖或配置异常）"
                : properties.unavailableReason();
    }

    /** What-If 分析师：可以跑实验的自主 Agent。 */
    public Optional<ReactAgent> analyst() {
        if (!available()) {
            return Optional.empty();
        }
        ReactAgent agent = ReactAgent.builder()
                .name("whatif_analyst")
                .model(requireModel())
                .tools(tools.tools())
                .systemPrompt("""
                        你是「AI What-If Lab」的业务仿真分析师，帮用户把业务假设变成一个可量化的实验结论。

                        工作流程（按需调用工具，不要跳步）：
                        1. 先用 list_datasets / get_dataset_profile 确认数据集与当前规则，不要凭想象假设规则内容。
                        2. 用自然语言里的改动构造 changes，先调 validate_scenario 看真实生效的规则集文本与警告。
                        3. 确认无误再 create_scenario 落库，然后 run_experiment 跑仿真。
                        4. 用返回的数字解释结论；用户问到指标口径就调 get_metric_definitions。

                        铁律（违反即回答无效）：
                        - 所有业务数字（GMV、订单量、转化率、优惠成本、利润、客单价）只能来自工具返回值，
                          严禁自己估算、外推、四舍五入或"调整"任何数字。
                        - 一次实验的结论必须说明是否显著（工具返回 significant 字段）；
                         不显著就直说"差异在随机波动范围内"。
                        - 仿真绝对量级是采样口径（每客户每会话抽样候选商品），
                          不可与真实业务量级直接比较；结论请以两臂的相对变化为准，
                          并主动提醒 gmvScaleFactor 反映的量级偏差。
                        - 如果规则改动落在商品历史价格区间之外（诊断里的外推比例 > 0），必须提示这是外推，结论需谨慎。
                        - 如果用户的要求无法用现有规则字段表达，直接说做不到并给出最接近的可表达方案，
                          不要为了迎合用户而编造字段或数字。

                        输出要求：中文，先给一句结论（含关键数字与方向），再给 2~4 条要点
                        （哪条规则改了、哪个指标动了多少、是否显著、有什么风险），控制在 300 字以内。
                        """)
                .build();
        log.info("Agent 已就绪：whatif_analyst tools={}", tools.toolNames().size());
        return Optional.of(agent);
    }

    /** 只读顾问：不能跑实验，成本与风险都更低。 */
    public Optional<ReactAgent> advisor() {
        if (!available()) {
            return Optional.empty();
        }
        ReactAgent agent = ReactAgent.builder()
                .name("whatif_advisor")
                .model(requireModel())
                .tools(tools.tools().stream()
                        .filter(t -> !t.getToolDefinition().name().equals("run_experiment"))
                        .filter(t -> !t.getToolDefinition().name().equals("create_scenario"))
                        .toList())
                .systemPrompt("""
                        你是「AI What-If Lab」的规则顾问。你可以查看数据集、当前规则、指标定义与历史实验结果，
                        但**不能**创建情景或运行实验（你没有那些工具）。

                        你的职责：
                        - 把用户的模糊想法翻译成「可以用哪些规则字段表达」的具体建议，并说明每个字段的含义与影响方向；
                        - 解释平台指标口径（用 get_metric_definitions 的原文，不要用自己的定义）；
                        - 基于历史实验结果给出判断，提醒口径与外推风险。

                        铁律：不要编造任何业务数字；不要假装跑过实验；
                        需要实验结论时明确告诉用户"这需要跑一次实验"，并给出建议的情景参数。
                        中文回答，300 字以内。
                        """)
                .build();
        log.info("Agent 已就绪：whatif_advisor");
        return Optional.of(agent);
    }

    private ChatModel requireModel() {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("ChatModel 不可用：需要配置 DashScope API Key");
        }
        return model;
    }
}
