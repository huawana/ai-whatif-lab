package com.whatif.lab.ai.schema;

import com.whatif.lab.data.profile.ColumnProfile;
import com.whatif.lab.data.profile.DataProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 启发式判列 —— **确定性基线**，也是无 AI Key 时的降级路径。
 *
 * <p><b>它的诚实定位</b>：启发式只能靠"列名 + 统计量"。列名换了语言/换了叫法就废
 * （Olist 的 {@code price} 好认，但 {@code payment_value_sum} 只能靠猜）。
 * 它存在的意义不是"够用"，而是给 LLM 一个**可比较的基线**：
 * 黄金集上两条路径都跑，LLM 到底高出多少是可以数出来的 —— 没有基线，"AI 判得更准"就只是句口号。
 *
 * <p>每条判定都带证据，且证据只写**画像里查得到的事实或列名匹配**，不许写"我觉得"。
 */
@Component
public class HeuristicSchemaMapper {

    /** 判列结果：角色 + 事件类型的成交/取消取值。 */
    public record Mapped(List<RoleAssignment> columns,
                         List<String> positiveEventValues,
                         List<String> negativeEventValues,
                         List<String> warnings) {
    }

    /** 一个角色在某列上的得分与理由。 */
    private record Score(double score, String evidence) {
    }

    /** 多词提示：只有在"去分隔符的紧凑名"里匹配才有意义（UnitPrice → unitprice）。 */
    private static final Map<String, String[]> COMPACT_HINTS = new LinkedHashMap<>();

    private static final Map<String, String[]> HINTS = new LinkedHashMap<>();
    private static final String[] POSITIVE_EVENTS = {"purchase", "buy", "transaction", "order", "paid",
            "payment", "complete", "completed", "success", "成交", "支付", "购买"};
    private static final String[] NEGATIVE_EVENTS = {"cancel", "cancellation", "refund", "return", "void",
            "fail", "failed", "取消", "退货", "退款"};

    static {
        COMPACT_HINTS.put("UNIT_PRICE", new String[]{"unitprice"});
        COMPACT_HINTS.put("ITEM_ID", new String[]{"productid", "itemid", "stockcode", "skuid"});
        COMPACT_HINTS.put("ENTITY_ID", new String[]{"customerid", "userid", "playerid", "memberid"});
        HINTS.put("ENTITY_ID", new String[]{"customer", "user", "player", "member", "visitor", "uid", "account",
                "客户", "用户", "玩家"});
        HINTS.put("ITEM_ID", new String[]{"stock", "product", "item", "sku", "goods", "商品", "道具", "货号"});
        HINTS.put("EVENT_TIME", new String[]{"time", "date", "timestamp", "时间", "日期"});
        HINTS.put("QUANTITY", new String[]{"qty", "quantity", "count", "数量", "件数"});
        HINTS.put("UNIT_PRICE", new String[]{"unitprice", "unit_price", "price", "单价", "价格"});
        HINTS.put("AMOUNT", new String[]{"amount", "revenue", "total", "value", "payment", "sales", "gmv",
                "金额", "销售额"});
        HINTS.put("ORDER_ID", new String[]{"order", "invoice", "transaction", "session", "订单", "单号"});
        HINTS.put("EVENT_TYPE", new String[]{"event", "action", "behavior", "事件"});
        HINTS.put("CATEGORY", new String[]{"country", "region", "city", "channel", "category", "segment",
                "group", "grade", "地区", "渠道", "分类", "等级"});
        HINTS.put("DESCRIPTION", new String[]{"description", "desc", "title", "名称", "描述"});
        HINTS.put("LEVER", new String[]{"discount", "rate", "budget", "spend", "折扣", "投放", "预算"});
        HINTS.put("OUTCOME", new String[]{"target", "outcome", "churn", "attrition", "purchased", "converted",
                "label", "是否", "结果"});
        HINTS.put("COVARIATE", new String[]{"weather", "temp", "season", "holiday", "humidity", "windspeed",
                "天气", "季节", "节假日"});
        HINTS.put("MEASURE", new String[]{"duration", "balance", "age", "cnt", "时长", "余额", "年龄"});
    }

    /** 这些角色**最多一个**（多了就是结构冲突，交给校验器报错）。 */
    private static final List<ColumnRole> SINGULAR = List.of(
            ColumnRole.ENTITY_ID, ColumnRole.ITEM_ID, ColumnRole.EVENT_TIME, ColumnRole.QUANTITY,
            ColumnRole.UNIT_PRICE, ColumnRole.AMOUNT, ColumnRole.ORDER_ID, ColumnRole.EVENT_TYPE);

    public Mapped map(DataProfile profile) {
        List<String> warnings = new ArrayList<>();
        Map<String, Map<ColumnRole, Score>> scores = new LinkedHashMap<>();
        profile.columns().forEach(c -> scores.put(c.name(), new LinkedHashMap<>()));

        for (ColumnProfile c : profile.columns()) {
            String name = c.name().toLowerCase(Locale.ROOT);
            String compact = name.replaceAll("[_\\s-]", "");

            // ① 画像自己报出来的**事实**优先（比列名可靠）
            if (c.flags().contains(ColumnProfile.PII_EMAIL) || c.flags().contains(ColumnProfile.PII_PHONE)) {
                add(scores, c, ColumnRole.PII, 0.95, "画像标记：" + String.join(",", c.flags()));
            }
            if (c.flags().contains(ColumnProfile.CONSTANT)) {
                add(scores, c, ColumnRole.IGNORE, 0.95, "常量列（唯一值=1），对建模零信息");
            }
            if ("DATE".equals(c.inferredType()) || "DATETIME".equals(c.inferredType())) {
                add(scores, c, ColumnRole.EVENT_TIME, 0.7,
                        "类型=" + c.inferredType() + "，取值样例 " + first(c));
            }
            // ② 列名匹配：**按词匹配，不按子串**。
            // 踩过的坑：子串匹配把 EnvironmentSatisfaction 判成 EVENT_TYPE（因为 satisfaction 里有 "action"）、
            // 把 OverTime 判成 EVENT_TIME（因为含 "time"）。切词之后这两个误判都不再发生。
            List<String> tokens = tokens(c.name());
            for (Map.Entry<String, String[]> e : HINTS.entrySet()) {
                ColumnRole role = ColumnRole.valueOf(e.getKey());
                for (String h : e.getValue()) {
                    if (tokens.contains(h)) {
                        if (!typeAllows(c, role)) {
                            continue;
                        }
                        add(scores, c, role, 0.8, "列名分词含「" + h + "」且类型=" + c.inferredType()
                                + " → 命中 " + e.getKey() + " 线索");
                        break;
                    }
                }
            }
            for (Map.Entry<String, String[]> e : COMPACT_HINTS.entrySet()) {
                ColumnRole role = ColumnRole.valueOf(e.getKey());
                for (String h : e.getValue()) {
                    if (compact.contains(h)) {
                        add(scores, c, role, 0.85, "列名紧凑形式含「" + h + "」→ 命中 " + e.getKey() + " 线索");
                        break;
                    }
                }
            }
            if ((name.endsWith("id") || name.equals("id")) && !scores.get(c.name()).containsKey(ColumnRole.ENTITY_ID)) {
                add(scores, c, ColumnRole.ENTITY_ID, 0.4, "列名以 id 结尾，但没命中更具体的主体线索");
            }
        }
        // 兜底：数值列若无人认领 → MEASURE（避免"没人认领"被静默忽略掉）
        for (ColumnProfile c : profile.columns()) {
            boolean numeric = "INT".equals(c.inferredType()) || "DECIMAL".equals(c.inferredType());
            if (numeric && scores.get(c.name()).isEmpty()) {
                add(scores, c, ColumnRole.MEASURE, 0.3, "数值列，未命中任何具体线索 → 归为度量");
            }
        }

        // 单值角色：全局贪心挑最高分（防止两列抢同一个角色）。
        // 但**画像事实优先**：常量列/PII 一旦被事实钉住（≥0.9），就不再参与竞争
        // —— 否则 EmployeeCount（常量 1）会被 "count" 抢去做 QUANTITY。
        Map<ColumnRole, String> winners = new LinkedHashMap<>();
        List<String> locked = new ArrayList<>();
        for (ColumnProfile c : profile.columns()) {
            for (ColumnRole factual : List.of(ColumnRole.PII, ColumnRole.IGNORE)) {
                Score s = scores.get(c.name()).get(factual);
                if (s != null && s.score() >= 0.9) {
                    locked.add(c.name());
                }
            }
        }
        for (ColumnRole role : SINGULAR) {
            String best = null;
            double bestScore = 0;
            for (ColumnProfile c : profile.columns()) {
                if (locked.contains(c.name())) {
                    continue;
                }
                Score s = scores.get(c.name()).get(role);
                if (s != null && s.score() > bestScore) {
                    bestScore = s.score();
                    best = c.name();
                }
            }
            if (best != null) {
                winners.put(role, best);
            }
        }

        List<RoleAssignment> out = new ArrayList<>();
        for (ColumnProfile c : profile.columns()) {
            ColumnRole chosen = null;
            Score chosenScore = null;
            for (ColumnRole role : SINGULAR) {
                if (c.name().equals(winners.get(role))) {
                    chosen = role;
                    chosenScore = scores.get(c.name()).get(role);
                    break;
                }
            }
            if (chosen == null) {
                for (Map.Entry<ColumnRole, Score> e : scores.get(c.name()).entrySet()) {
                    if (SINGULAR.contains(e.getKey())) {
                        continue;
                    }
                    if (chosenScore == null || e.getValue().score() > chosenScore.score()) {
                        chosen = e.getKey();
                        chosenScore = e.getValue();
                    }
                }
            }
            if (chosen == null) {
                chosen = ColumnRole.IGNORE;
                chosenScore = new Score(0.4, "没有任何线索命中 → 默认忽略（需人工确认）");
            }
            out.add(new RoleAssignment(c.name(), chosen, chosenScore.score(), chosenScore.evidence(),
                    RoleAssignment.Decider.HEURISTIC));
        }

        // 事件类型列的成交/取消取值：按同义词表匹配该列的 Top 取值（是匹配，不是猜）
        List<String> pos = new ArrayList<>();
        List<String> neg = new ArrayList<>();
        out.stream().filter(r -> r.role() == ColumnRole.EVENT_TYPE).findFirst().ifPresent(r -> {
            ColumnProfile c = profile.columns().stream().filter(x -> x.name().equals(r.column()))
                    .findFirst().orElse(null);
            if (c != null && c.topValues() != null) {
                for (String v : c.topValues()) {
                    String lv = v.toLowerCase(Locale.ROOT);
                    for (String s : POSITIVE_EVENTS) {
                        if (lv.contains(s)) {
                            pos.add(v);
                            break;
                        }
                    }
                    for (String s : NEGATIVE_EVENTS) {
                        if (lv.contains(s)) {
                            neg.add(v);
                            break;
                        }
                    }
                }
            }
            if (pos.isEmpty()) {
                warnings.add("事件类型列「" + r.column() + "」的取值里没有命中成交同义词，无法确定哪种事件算购买（取值："
                        + (c == null ? "?" : c.topValues()) + "）");
            }
        });
        return new Mapped(out, pos, neg, warnings);
    }

    /**
     * 列名切词：驼峰与分隔符都要切开。
     * {@code EnvironmentSatisfaction → [environment, satisfaction]}、{@code unit_price → [unit, price]}。
     */
    static List<String> tokens(String name) {
        String s = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(s.split("[^a-z0-9\\u4e00-\\u9fff]+"))
                .filter(t -> !t.isBlank()).toList();
    }

    /** 类型门禁：角色对类型有要求时，类型不符就不给这一分（防止 OverTime 冒充时间列）。 */
    private static boolean typeAllows(ColumnProfile c, ColumnRole role) {
        boolean numeric = "INT".equals(c.inferredType()) || "DECIMAL".equals(c.inferredType());
        boolean temporal = "DATE".equals(c.inferredType()) || "DATETIME".equals(c.inferredType());
        return switch (role) {
            case EVENT_TIME -> temporal;
            case QUANTITY, UNIT_PRICE, AMOUNT, LEVER, MEASURE -> numeric;
            case EVENT_TYPE -> !temporal;
            default -> true;
        };
    }

    private static void add(Map<String, Map<ColumnRole, Score>> scores, ColumnProfile c,
                            ColumnRole role, double score, String evidence) {
        Map<ColumnRole, Score> m = scores.get(c.name());
        Score cur = m.get(role);
        if (cur == null || score > cur.score()) {
            m.put(role, new Score(score, evidence));
        }
    }

    private static String first(ColumnProfile c) {
        return c.samples() == null || c.samples().isEmpty() ? "?" : c.samples().get(0);
    }
}
