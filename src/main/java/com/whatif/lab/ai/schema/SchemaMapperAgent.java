package com.whatif.lab.ai.schema;

import com.whatif.lab.common.JsonCodec;
import com.whatif.lab.ai.AiInvoker;
import com.whatif.lab.ai.config.AiProperties;
import com.whatif.lab.data.profile.ColumnProfile;
import com.whatif.lab.data.profile.DataProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用 LLM 把**数据画像**映射成"每列是什么角色"。
 *
 * <p><b>三条护栏（设计稿第 5 节，都在这里落地）</b>：
 * <ol>
 *   <li><b>LLM 只看画像、不看原始表</b>：画像含统计量与少量样本值，
 *       但 <b>PII 列的样本值会被隐藏</b>（真实业务表里有手机号/邮箱）；</li>
 *   <li><b>LLM 不产数字、不写配置</b>：它只输出角色（闭集枚举）+ 置信度 + 证据，
 *       领域配置由 {@link RoleDeriver} 确定性推导；</li>
 *   <li><b>不确定必须说不知道</b>：提示词明确授权"放进 unknowns"，且低置信度会被校验器拦下。</li>
 * </ol>
 *
 * <p>失败处理与 {@code ScenarioCompiler} 一致：把校验器的报错**回喂**给模型重试；
 * 上游调用失败则立即返回（模型压根没答，再问也是白花钱）。
 */
@Component
public class SchemaMapperAgent {

    public static final String PROMPT_VERSION = "schema-mapper-v1";

    private static final Logger log = LoggerFactory.getLogger(SchemaMapperAgent.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final AiInvoker invoker;
    private final AiProperties properties;
    private final JsonCodec json;
    private final SchemaValidator validator;

    public SchemaMapperAgent(ObjectProvider<ChatModel> chatModelProvider,
                             AiInvoker invoker,
                             AiProperties properties,
                             JsonCodec json,
                             SchemaValidator validator) {
        this.chatModelProvider = chatModelProvider;
        this.invoker = invoker;
        this.properties = properties;
        this.json = json;
        this.validator = validator;
    }

    public boolean available() {
        return properties.isUsable() && chatModelProvider.getIfAvailable() != null;
    }

    public String unavailableReason() {
        return properties.unavailableReason() == null
                ? "ChatModel 未装配（依赖或配置异常）"
                : properties.unavailableReason();
    }

    /** LLM 的原始输出（已解析）：只含角色与事件取值，不含任何可执行配置。 */
    public record LlmOutput(String declaredDomain,
                            String templateHint,
                            List<RoleAssignment> columns,
                            List<String> positiveEventValues,
                            List<String> negativeEventValues,
                            List<SchemaProposal.Unknown> unknowns,
                            List<String> notes,
                            String raw,
                            int attempts,
                            String error) {
    }

    public LlmOutput propose(DataProfile profile) {
        if (!available()) {
            return new LlmOutput(null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                    null, 0, unavailableReason());
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        String system = systemPrompt();
        List<String> feedback = new ArrayList<>();
        String lastRaw = null;
        int maxAttempts = Math.max(1, properties.compileRetries() + 1);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String user = userPrompt(profile)
                    + (feedback.isEmpty() ? ""
                    : "\n\n上一次你的输出被确定性校验拒绝，错误如下：\n- " + String.join("\n- ", feedback)
                      + "\n请修正后重新**只输出 JSON**。");
            AiInvoker.Result result = invoker.call(chatModel, system, user);
            if (!result.isOk()) {
                log.warn("Schema Mapper：上游调用失败 attempt={} error={}", attempt, result.error());
                return new LlmOutput(null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                        lastRaw, attempt, result.error());
            }
            lastRaw = result.text();
            try {
                LlmOutput parsed = parse(lastRaw, profile);
                // 用确定性校验器当"编译器"：角色引用不存在的列/角色名不在闭集 → 回喂重试
                List<String> errors = new ArrayList<>();
                Map<String, ColumnProfile> byName = new LinkedHashMap<>();
                profile.columns().forEach(c -> byName.put(c.name(), c));
                for (RoleAssignment a : parsed.columns()) {
                    if (!byName.containsKey(a.column())) {
                        errors.add("列名不存在：" + a.column());
                    }
                }
                if (parsed.columns().isEmpty()) {
                    errors.add("columns 为空：必须给每一列一个角色");
                }
                if (!errors.isEmpty()) {
                    feedback.addAll(errors);
                    log.warn("Schema Mapper 第 {} 次输出未通过引用检查：{}", attempt, errors);
                    continue;
                }
                log.info("Schema Mapper 成功 attempt={} columns={}", attempt, parsed.columns().size());
                return new LlmOutput(parsed.declaredDomain(), parsed.templateHint(), parsed.columns(),
                        parsed.positiveEventValues(), parsed.negativeEventValues(), parsed.unknowns(),
                        parsed.notes(), lastRaw, attempt, null);
            } catch (Exception e) {
                feedback.add(e.getMessage() == null ? e.toString() : e.getMessage());
                log.warn("Schema Mapper 第 {} 次输出无法解析：{}", attempt, e.getMessage());
            }
        }
        return new LlmOutput(null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                lastRaw, maxAttempts, "模型输出经 " + maxAttempts + " 次尝试仍无法通过解析，最后反馈：" + feedback);
    }

    private String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据表的语义标注器。你的唯一任务是：给 CSV 的每一列标注一个角色。\n\n")
          .append("**绝对禁止**：\n")
          .append("1. 不许编造列名（只能用用户给你的列名，逐字复制）；\n")
          .append("2. 不许输出任何数字、金额、统计值（你不负责算数）；\n")
          .append("3. 不许输出配置、SQL、代码；\n")
          .append("4. 不确定就放进 unknowns，**不要猜**。宁可说不知道，也不要给一个看起来合理的错答案。\n\n")
          .append("可选角色（只能用这些，拼写必须完全一致）：\n");
        ColumnRole.vocabulary().forEach(v -> sb.append("  - ").append(v).append('\n'));
        sb.append("\n角色语义要点：\n")
          .append("  - ENTITY_ID/ITEM_ID/EVENT_TIME 是抽取必需的三件套（谁、对什么、什么时候）；\n")
          .append("  - LEVER = **可被改变**的输入（价格、折扣、预算、投放）；OUTCOME = 会跟着变的结果（购买、金额、留存）；\n")
          .append("    COVARIATE = 影响结果但**不可操纵**（天气、季节、地区、等级）。这三者决定这张表能不能做 What-If；\n")
          .append("  - 只有 1 个取值/常量列 → IGNORE；已知结果标签（如是否流失）→ LABEL（有泄漏风险）；\n")
          .append("  - 邮箱/手机号等 → PII。\n\n")
          .append("只输出 JSON，格式：\n")
          .append("{\"declaredDomain\": \"你猜的领域名(英文小写，如 ecommerce/clickstream/subscription)\"," )
          .append(" \"templateHint\": \"BEHAVIOR_COUNTERFACTUAL_MONTE_CARLO\",")
          .append(" \"columns\": [{\"column\": \"原列名\", \"role\": \"ENTITY_ID\", \"confidence\": 0.95,")
          .append(" \"evidence\": \"依据：整数且重复出现/列名含 Customer\"}],")
          .append(" \"positiveEventValues\": [\"transaction\"], \"negativeEventValues\": [\"refund\"],")
          .append(" \"unknowns\": [{\"column\": \"x\", \"question\": \"需要确认什么\", \"why\": \"为什么不确定\"}],")
          .append(" \"notes\": \"一句总结\"}\n")
          .append("（positiveEventValues/negativeEventValues 只在该表有 EVENT_TYPE 列时才有意义：")
          .append("填该列里哪些取值算成交/取消，取值必须逐字来自样本值。）\n\n")
          .append("示例（输入是别的表，仅示范格式与判断口径）：\n")
          .append("输入列：buyer_id(唯一值 20000，数值)、item_code(唯一值 300)、ts(时间)、n(整数,含负值)、")
          .append("unit_yuan(小数,非负)、channel(唯一值 6)、is_refund(0/1)\n")
          .append("输出：{\"declaredDomain\":\"retail\",\"templateHint\":\"BEHAVIOR_COUNTERFACTUAL_MONTE_CARLO\",")
          .append("\"columns\":[{\"column\":\"buyer_id\",\"role\":\"ENTITY_ID\",\"confidence\":0.95,")
          .append("\"evidence\":\"列名含 buyer，高基数标识\"},")
          .append("{\"column\":\"item_code\",\"role\":\"ITEM_ID\",\"confidence\":0.9,\"evidence\":\"列名含 item\"},")
          .append("{\"column\":\"ts\",\"role\":\"EVENT_TIME\",\"confidence\":0.95,\"evidence\":\"时间类型\"},")
          .append("{\"column\":\"n\",\"role\":\"QUANTITY\",\"confidence\":0.85,\"evidence\":\"整数且含负值（退货惯例）\"},")
          .append("{\"column\":\"unit_yuan\",\"role\":\"UNIT_PRICE\",\"confidence\":0.9,\"evidence\":\"小数非负=单价\"},")
          .append("{\"column\":\"channel\",\"role\":\"COVARIATE\",\"confidence\":0.8,\"evidence\":\"低基数分组维度\"},")
          .append("{\"column\":\"is_refund\",\"role\":\"LABEL\",\"confidence\":0.7,\"evidence\":\"0/1 结果标志，有泄漏风险\"}],")
          .append("\"positiveEventValues\":[],\"negativeEventValues\":[],\"unknowns\":[],\"notes\":\"标准交易明细表\"}");
        return sb.toString();
    }

    /** 把画像渲染成给模型的紧凑表格。**PII 列的取值一律隐藏**。 */
    private String userPrompt(DataProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是一份 CSV 的数据画像（共 ").append(profile.columnCount()).append(" 列，")
          .append(profile.rowCount()).append(" 行，编码 ").append(profile.charset())
          .append("，分隔符「").append(profile.delimiter()).append("」）。\n")
          .append("请给每一列标注角色。\n\n")
          .append("列名 | 类型 | 空值率 | 唯一值 | 数值范围/长度 | 样本值 | 事实标记\n");
        for (ColumnProfile c : profile.columns()) {
            boolean pii = c.flags().contains(ColumnProfile.PII_EMAIL) || c.flags().contains(ColumnProfile.PII_PHONE);
            String samples = pii ? "(已隐藏：疑似个人信息)" : String.join(", ", c.samples());
            String range = c.min() != null
                    ? fmt(c.min()) + "~" + fmt(c.max()) + (c.p05() != null ? " (P5~P95 " + fmt(c.p05()) + "~" + fmt(c.p95()) + ")" : "")
                    : (c.minLength() != null ? "长度 " + c.minLength() + "~" + c.maxLength() : "");
            sb.append("- ").append(c.name()).append(" | ").append(c.inferredType())
              .append(" | ").append(String.format(Locale.ROOT, "%.4f", c.nullRate()))
              .append(" | ").append(c.distinctCapped() ? ">=50000" : c.distinctCount())
              .append(" | ").append(range)
              .append(" | ").append(samples)
              .append(" | ").append(String.join(",", c.flags()))
              .append('\n');
        }
        if (!profile.warnings().isEmpty()) {
            sb.append("\n画像给出的告警（供参考）：\n");
            profile.warnings().forEach(w -> sb.append("  - ").append(w).append('\n'));
        }
        sb.append("\n记住：只输出 JSON；列名逐字复制；不确定的放 unknowns。");
        return sb.toString();
    }

    private static String fmt(Double d) {
        if (d == null) {
            return "?";
        }
        return Math.abs(d - Math.rint(d)) < 1e-9 ? String.valueOf(d.longValue()) : String.format(Locale.ROOT, "%.4f", d);
    }

    @SuppressWarnings("unchecked")
    private LlmOutput parse(String raw, DataProfile profile) {
        String jsonText = extractJson(raw);
        Map<String, Object> root = json.read(jsonText, Map.class);
        List<RoleAssignment> columns = new ArrayList<>();
        Object cols = root.get("columns");
        if (cols instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String column = str(m.get("column"));
                String role = str(m.get("role"));
                if (column == null || role == null) {
                    throw new IllegalArgumentException("columns 元素缺 column 或 role");
                }
                ColumnRole cr;
                try {
                    cr = ColumnRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("未知角色「" + role + "」（只能用给定词表）");
                }
                double conf = parseDouble(m.get("confidence"), 0.8);
                columns.add(new RoleAssignment(column, cr, conf, str(m.get("evidence")),
                        RoleAssignment.Decider.LLM));
            }
        }
        List<SchemaProposal.Unknown> unknowns = new ArrayList<>();
        if (root.get("unknowns") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    unknowns.add(new SchemaProposal.Unknown(str(m.get("column")),
                            str(m.get("question")), str(m.get("why"))));
                }
            }
        }
        return new LlmOutput(str(root.get("declaredDomain")), str(root.get("templateHint")),
                columns, strList(root.get("positiveEventValues")), strList(root.get("negativeEventValues")),
                unknowns, strList(root.get("notes")), raw, 1, null);
    }

    private static double parseDouble(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? fallback : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x != null) {
                    out.add(String.valueOf(x));
                }
            }
        }
        return out;
    }

    /** 从可能带 markdown 围栏的输出里抠出 JSON 对象。 */
    static String extractJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("模型没有返回内容");
        }
        String s = raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.indexOf("```", start + 1);
            if (start > 0 && end > start) {
                s = s.substring(start + 1, end).trim();
            }
        }
        int i = s.indexOf('{');
        int j = s.lastIndexOf('}');
        if (i < 0 || j <= i) {
            throw new IllegalArgumentException("输出里找不到 JSON 对象：" + s.substring(0, Math.min(120, s.length())));
        }
        return s.substring(i, j + 1);
    }
}
