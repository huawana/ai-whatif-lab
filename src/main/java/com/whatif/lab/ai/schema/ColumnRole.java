package com.whatif.lab.ai.schema;

import java.util.Arrays;
import java.util.List;

/**
 * 列角色**闭集** —— LLM 只能在这里面选，选别的会被确定性校验器直接拒掉。
 *
 * <p><b>为什么必须是闭集</b>：开放词表意味着"LLM 说什么就是什么"，下游没法校验、
 * 也没法统计准确率。闭集让"判列"变成一个**可度量**的任务：
 * 黄金集里每列有一个标准答案，判对/判错/拒答都能数出来。
 *
 * <p>两类角色（设计稿 3.2）：
 * <ul>
 *   <li><b>结构角色</b>：这张表长什么样（谁、对什么、什么时候、多少钱）→ 决定能不能抽取；</li>
 *   <li><b>因果角色</b>：这张表能不能做 What-If（哪些列能被改、哪些列会跟着变）→ 决定能跑哪种工况。</li>
 * </ul>
 * 分开的理由：结构齐全但**没有杠杆**的表（IBM HR：有结果列但没有"可改的输入"）
 * 依然做不了反事实 —— 只看结构会误判成"能用"。
 */
public enum ColumnRole {

    // ---- 结构角色 ----
    ENTITY_ID("entityId", Group.STRUCTURAL, "行为主体标识（客户/玩家/用户）"),
    ITEM_ID("productId", Group.STRUCTURAL, "被操作对象标识（商品/道具）"),
    EVENT_TIME("eventTime", Group.STRUCTURAL, "事件发生时间"),
    QUANTITY("quantity", Group.STRUCTURAL, "数量"),
    UNIT_PRICE("unitPrice", Group.STRUCTURAL, "单价"),
    AMOUNT("amount", Group.STRUCTURAL, "金额（原生列）"),
    ORDER_ID("orderId", Group.STRUCTURAL, "单据/会话号"),
    CATEGORY("group", Group.STRUCTURAL, "分组维度（渠道/地区/等级）"),
    DESCRIPTION("description", Group.STRUCTURAL, "描述文本"),
    /**
     * 事件类型列（view / addtocart / transaction）—— 设计稿 v1 漏了这个角色，
     * 但点击流数据没有它就表达不了"哪种事件算成交"（RetailRocket 实测：只有 transaction 行才有 transactionid）。
     * 它没有标准字段落点：作用是在推导时生成 classify 规则，而不是填进抽取器。
     */
    EVENT_TYPE(null, Group.STRUCTURAL, "事件类型（view/addtocart/purchase/refund…）"),

    // ---- 因果角色（决定能不能做 What-If）----
    LEVER(null, Group.CAUSAL, "可操纵的输入：价格/折扣/预算/投放"),
    OUTCOME(null, Group.CAUSAL, "可观测的结果：购买/金额/用量/留存"),
    COVARIATE(null, Group.CAUSAL, "不可操纵但影响结果：天气/季节/地区/等级"),

    // ---- 其他 ----
    LABEL(null, Group.OTHER, "已知结果/标签列（泄漏风险，默认排除）"),
    MEASURE(null, Group.OTHER, "数值度量（非金额/数量：余额、时长、计数、年龄）"),
    TEXT(null, Group.OTHER, "自由文本"),
    PII(null, Group.OTHER, "个人信息（绝不进模型，且不进给 LLM 的样本值）"),
    IGNORE(null, Group.OTHER, "明确忽略（行号/常量/近重复）");

    /** 角色分组。 */
    public enum Group { STRUCTURAL, CAUSAL, OTHER }

    private final String canonicalField;
    private final Group group;
    private final String description;

    ColumnRole(String canonicalField, Group group, String description) {
        this.canonicalField = canonicalField;
        this.group = group;
        this.description = description;
    }

    /** 该角色对应的**标准字段名**（抽取器认识的那 8 个之一；没有则 null，表示不进抽取）。 */
    public String canonicalField() {
        return canonicalField;
    }

    public Group group() {
        return group;
    }

    public String description() {
        return description;
    }

    /** 拼给 LLM 的角色清单（含说明，避免模型猜语义）。 */
    public static List<String> vocabulary() {
        return Arrays.stream(values()).map(r -> r.name() + " = " + r.description).toList();
    }
}
