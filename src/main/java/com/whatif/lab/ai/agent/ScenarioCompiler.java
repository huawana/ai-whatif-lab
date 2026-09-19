package com.whatif.lab.ai.agent;

import com.whatif.lab.ai.AiInvoker;
import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.common.BizException;
import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.domain.scenario.ScenarioDsl;
import com.whatif.lab.service.DatasetService;
import com.whatif.lab.service.ScenarioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario 编译器：自然语言 → Scenario DSL。
 *
 * <p><b>这是 LLM 在本项目里唯一的「产出」职责</b>：把「把满100减10改成满80减15，
 * 模拟30天」翻译成结构化 DSL。它<b>不产出任何业务数字</b> —— 所有指标由仿真引擎算。
 *
 * <p><b>三层防幻觉</b>（对应文档第十三节「LLM 是编译器前端」）：
 * <ol>
 *   <li><b>受约束的输出格式</b>：提示词里给出 DSL 的精确形状与合法字段枚举，
 *       并明确要求「只输出 JSON」；</li>
 *   <li><b>校验即闸门</b>：模型输出先过 {@link ScenarioService#validate}（结构/语义/业务三层），
 *       <b>不合法就不放行</b>；</li>
 *   <li><b>自我纠正循环</b>：校验失败时把<b>具体的错误信息</b>回喂给模型让它改（最多 N 次），
 *       而不是重复问同样的问题。这是「把编译器的报错当输入」的做法，
 *       实测比让模型自由发挥的准确率高得多。</li>
 * </ol>
 *
 * <p>返回值里带 {@code rawModelOutput} 与 {@code attempts}：
 * 让调用方看得见「模型到底写了什么、改了几次」—— 幻觉必须可见，否则无法审计。
 */
@Component
public class ScenarioCompiler {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCompiler.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AiProperties properties;
    private final AiInvoker invoker;
    private final DatasetService datasetService;
    private final ScenarioService scenarioService;
    private final JsonCodec json;

    public ScenarioCompiler(ObjectProvider<ChatModel> chatModelProvider,
                            AiProperties properties,
                            AiInvoker invoker,
                            DatasetService datasetService,
                            ScenarioService scenarioService,
                            JsonCodec json) {
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
        this.invoker = invoker;
        this.datasetService = datasetService;
        this.scenarioService = scenarioService;
        this.json = json;
    }

    /** 编译结果（成功时 dsl 非空）。 */
    /**
     * 编译结果。
     *
     * @param error         <b>模型输出被校验拒绝</b>的原因（模型正常工作，但输出不合规）
     * @param upstreamError <b>模型服务本身没调通</b>的原因（超时 / 限流 / 欠费 / 5xx）。
     *                      与 error 必须分开：前者重试无意义，后者可以重试；两者都要能给出明确原因。
     */
    public record Compilation(boolean success,
                              ScenarioDsl dsl,
                              Map<String, Object> validation,
                              String rawModelOutput,
                              int attempts,
                              String error,
                              String upstreamError) {

        /** 上游是否压根没调通（这种失败不该被当成"模型输出有问题"）。 */
        public boolean upstreamFailed() {
            return upstreamError != null && !upstreamError.isBlank();
        }
    }

    public boolean available() {
        return properties.isUsable() && chatModelProvider.getIfAvailable() != null;
    }

    public String unavailableReason() {
        return properties.unavailableReason() == null
                ? "ChatModel 未装配（依赖或配置异常）"
                : properties.unavailableReason();
    }

    /**
     * 编译自然语言为 DSL。
     *
     * @param datasetId 数据集（决定规则集与合法字段）
     * @param text      用户原话
     */
    public Compilation compile(Long datasetId, String text) {
        if (!available()) {
            throw BizException.aiDisabled(unavailableReason()
                    + "。你仍然可以手工提交 Scenario DSL：POST /api/scenarios 或 "
                    + "POST /api/experiments 带 dsl 字段。");
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        Map<String, Object> profile = datasetService.profile(datasetId);
        String systemPrompt = systemPrompt(profile);

        List<String> feedback = new ArrayList<>();
        String lastRaw = null;
        int attempts = 0;
        int maxAttempts = Math.max(1, properties.compileRetries() + 1);

        while (attempts < maxAttempts) {
            attempts++;
            String userPrompt = attempts == 1
                    ? "用户原话：" + text
                    : "用户原话：" + text + "\n\n上一次你的输出被校验拒绝，错误如下：\n"
                    + String.join("\n", feedback)
                    + "\n请修正后重新只输出 JSON。";

            AiInvoker.Result result = invoker.call(chatModel, systemPrompt, userPrompt);
            if (!result.isOk()) {
                // 上游失败立即返回，不做「重试校验」——模型根本没答，再问几次也是白花钱
                log.warn("场景编译器：上游调用失败 attempts={} error={}", attempts, result.error());
                return new Compilation(false, null, Map.of(), lastRaw, attempts, null, result.error());
            }
            lastRaw = result.text();
            try {
                ScenarioDsl dsl = parse(lastRaw, datasetId);
                ScenarioService.ValidationResult validation = scenarioService.validate(datasetId, dsl, null);
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("valid", validation.valid());
                v.put("message", validation.message());
                v.put("warnings", validation.warnings());
                v.put("baselineRuleSet", validation.baselineRules().describe());
                v.put("scenarioRuleSet", validation.scenarioRules().describe());
                v.put("dslHash", validation.dslHash());
                v.put("canonicalForm", validation.canonicalForm());
                log.info("Scenario 编译成功 datasetId={} attempts={} changes={}", datasetId, attempts,
                        dsl.changes().size());
                return new Compilation(true, dsl, v, lastRaw, attempts, null, null);
            } catch (Exception e) {
                String message = e instanceof BizException ? e.getMessage() : e.toString();
                feedback.add("- " + message);
                log.warn("Scenario 编译第 {} 次校验失败：{}", attempts, message);
            }
        }
        return new Compilation(false, null, Map.of(), lastRaw, attempts,
                "模型输出经 " + attempts + " 次尝试仍无法通过校验：" + String.join(" | ", feedback), null);
    }

    // ------------------------------------------------------------------ 提示词

    /**
     * 系统提示词 —— 工具描述之外的第二个「契约」。
     *
     * <p>写提示词的三条原则在这里都体现出来了：
     * ① 把可用字段<b>穷举</b>出来（模型不会去猜一个不存在的字段）；
     * ② 用 <b>few-shot</b> 给一个输入→输出的完整样例（比任何抽象描述都有效）；
     * ③ 明确<b>禁止编造</b>业务数字，并授权模型说「无法翻译」——
     *    这是让幻觉变成可检测错误的关键一句。
     */
    private String systemPrompt(Map<String, Object> profile) {
        Object rules = profile.get("currentRuleSet");
        Object fields = profile.get("editableRuleFields");
        return """
                你是「AI What-If Lab」平台的 Scenario 编译器。你的唯一职责是把用户的中文假设
                翻译成结构化 Scenario DSL（JSON），供确定性仿真引擎执行。

                【硬性约束】
                1. 只输出一个 JSON 对象，不要输出解释、不要输出 Markdown 代码块标记。
                2. 绝对不要输出任何业务指标数字（GMV、订单量、转化率、利润…）。
                   那些数字由仿真引擎计算，你算的每一个数都是错的。
                3. changes 是「相对当前规则集的差分」，只写用户明确提到的改动，不要输出完整规则集。
                4. 每个 change 形如 {"ruleType":"DISCOUNT","field":"amount","from":10,"to":15}。
                5. 如果用户的要求无法用可用字段表达（例如「提高品牌知名度」），
                   输出 {"error":"无法翻译的原因"}，不要硬凑。

                【当前数据集的规则集】
                %s

                【每种规则可改的字段】
                %s

                【字段语义】
                - DISCOUNT.threshold：满减门槛（订单金额达到它才减）
                - DISCOUNT.amount：减免金额；DISCOUNT.percent：按比例减免（0~1，与 amount 二选一）
                - PRICE.multiply：价格系数（0.9 = 降价 10%%）；PRICE.add：价格加项
                - PROBABILITY.multiplier：购买概率系数（1.1 = 提高 10%%）

                【输出格式】
                {"datasetId":%s,"name":"简短中文名","durationDays":30,
                 "changes":[{"ruleType":"DISCOUNT","field":"threshold","from":100,"to":80}],
                 "extras":{}}

                【样例】
                用户：如果满100减10改成满80减15，模拟未来30天的情况
                输出：{"datasetId":%s,"name":"满80减15","durationDays":30,
                 "changes":[{"ruleType":"DISCOUNT","field":"threshold","from":100,"to":80},
                            {"ruleType":"DISCOUNT","field":"amount","from":10,"to":15}],
                 "extras":{}}
                """.formatted(json.write(rules), json.write(fields), profile.get("datasetId"),
                profile.get("datasetId"));
    }

    /** 从模型输出里抽出 JSON（容忍代码块、前后废话、以及 {} 里的嵌套）。 */
    ScenarioDsl parse(String raw, Long datasetId) {
        String jsonText = extractJson(raw);
        ScenarioDsl dsl = json.read(jsonText, ScenarioDsl.class);
        if (dsl == null) {
            throw BizException.validation("模型输出无法解析为 Scenario DSL");
        }
        if (dsl.changes() == null) {
            throw BizException.validation("DSL 缺少 changes 字段");
        }
        // datasetId 与 durationDays 以调用方为准，避免模型写错数据集
        return new ScenarioDsl(datasetId, dsl.name(),
                dsl.durationDays() == null ? 30 : dsl.durationDays(),
                dsl.changes(), dsl.extras() == null ? Map.of() : dsl.extras());
    }

    static String extractJson(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLineEnd > 0 && lastFence > firstLineEnd) {
                text = text.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw BizException.validation("模型输出里找不到 JSON 对象：" + abbreviate(raw));
        }
        return text.substring(start, end + 1);
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
