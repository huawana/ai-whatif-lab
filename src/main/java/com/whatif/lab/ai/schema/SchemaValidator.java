package com.whatif.lab.ai.schema;

import com.whatif.lab.data.csv.CsvTable;
import com.whatif.lab.data.profile.ColumnProfile;
import com.whatif.lab.data.profile.DataProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema 提案的**确定性校验器** —— 不管角色是 LLM 判的还是人写的，都得过这一关。
 *
 * <p><b>它在整个架构里的位置</b>：LLM 只能"提案"，不能"定案"。
 * 这里负责把提案变成一组**可证伪的检查**，任何一条不过就不许冻结、不许执行。
 * 这样"AI 判列"这件事才有边界 —— 否则就是"模型说啥是啥"，错得看不出来。
 *
 * <p><b>三类检查</b>：
 * <ol>
 *   <li><b>词汇/引用检查</b>：角色在闭集内、列名真的存在（防止 LLM 编字段名）；</li>
 *   <li><b>结构检查</b>：必需角色齐全且唯一、金额有唯一算法、类型与声明一致；</li>
 *   <li><b>数据检查（最重要）</b>：真的去抽样比对 数量×单价 与 金额 —— 语义冲突必须**转人工**，
 *       而不是静默挑一个。这条是唯一能发现"amount 其实是税后金额/成本"的办法。</li>
 * </ol>
 */
@Component
public class SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(SchemaValidator.class);

    /** 低于这个置信度就被移到 unknowns（不许自动冻结）。 */
    public static final double CONFIDENCE_THRESHOLD = 0.75;

    /** 数值冲突检测的抽样行数。 */
    private static final int SAMPLE_ROWS = 5000;

    /** 一致率低于此值 → 判定为语义冲突，转人工裁决。 */
    private static final double CONSISTENCY_FLOOR = 0.95;

    public record Result(List<String> errors,
                         List<String> warnings,
                         List<SchemaProposal.Unknown> conflicts,
                         SchemaProposal.Capability capability,
                         String capabilityReason) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public Result validate(SchemaProposal p, DataProfile profile, Path file) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>(p.warnings());
        List<SchemaProposal.Unknown> conflicts = new ArrayList<>(p.unknowns());

        // ---------- ① 引用检查：列必须真实存在（LLM 编字段名是最常见的失败模式） ----------
        Map<String, ColumnProfile> byName = new HashMap<>();
        profile.columns().forEach(c -> byName.put(c.name(), c));
        for (RoleAssignment a : p.columns()) {
            if (!byName.containsKey(a.column())) {
                errors.add("列不存在：\"" + a.column() + "\"（画像里共 " + byName.size() + " 列）");
            }
        }

        // ---------- ② 结构检查：必需角色齐全且唯一 ----------
        requireExactlyOne(p, ColumnRole.ENTITY_ID, errors);
        requireExactlyOne(p, ColumnRole.ITEM_ID, errors);
        requireExactlyOne(p, ColumnRole.EVENT_TIME, errors);
        requireAtMostOne(p, ColumnRole.AMOUNT, errors);

        boolean hasQty = !p.of(ColumnRole.QUANTITY).isEmpty();
        boolean hasPrice = !p.of(ColumnRole.UNIT_PRICE).isEmpty();
        boolean hasAmount = !p.of(ColumnRole.AMOUNT).isEmpty();
        if (!(hasAmount ^ (hasQty && hasPrice))) {
            errors.add("金额算法必须唯一：现在是 AMOUNT=" + hasAmount
                    + "、数量=" + hasQty + "、单价=" + hasPrice
                    + "（要「原生金额列」或「数量×单价」，不能两个都给也不能都不给）");
        }

        // ---------- ③ 类型检查：声明为数值的角色，画像必须证明它是数值 ----------
        for (RoleAssignment a : p.columns()) {
            ColumnProfile c = byName.get(a.column());
            if (c == null) {
                continue;
            }
            switch (a.role()) {
                case QUANTITY, UNIT_PRICE, AMOUNT, LEVER, MEASURE -> {
                    if (!"INT".equals(c.inferredType()) && !"DECIMAL".equals(c.inferredType())) {
                        errors.add("角色 " + a.role() + " 要求数值列，但 " + a.column()
                                + " 的类型是 " + c.inferredType());
                    }
                }
                case EVENT_TIME -> {
                    if (!"DATE".equals(c.inferredType()) && !"DATETIME".equals(c.inferredType())) {
                        errors.add("角色 EVENT_TIME 要求日期/时间列，但 " + a.column()
                                + " 的类型是 " + c.inferredType());
                    }
                }
                default -> {
                }
            }
        }

        // ---------- ④ 因果角色 → 工况判定（**由校验器算，不采信提案里的结论**） ----------
        // 杠杆/结果只认**置信度达标**的判定：否则一堆"名字里带 rate"的列就能把一张 HR 表
        // 说成决策型（实测发生过：DailyRate/HourlyRate 被启发式判成 LEVER）
        // 【角色冲突的处理】一个"单价"列同时是结构角色(UNIT_PRICE)和因果角色(可操纵的杠杆)，
        // 而一列只能有一个角色 → 杠杆信息会被结构角色吃掉（实测：零售表的 UnitPrice 让工况被降级成冲击型）。
        // 处理办法：**价格列本身就算杠杆** —— 引擎的 PRICE / DISCOUNT 规则就是作用在单价上的。
        // 这比"逼模型/人二选一"更贴近事实：价格既是结构，也是那个"能被改的输入"。
        boolean hasLever = p.of(ColumnRole.LEVER).stream()
                .anyMatch(a -> a.confidence() >= CONFIDENCE_THRESHOLD)
                || !p.of(ColumnRole.UNIT_PRICE).isEmpty();
        boolean hasOutcome = p.of(ColumnRole.OUTCOME).stream()
                .anyMatch(a -> a.confidence() >= CONFIDENCE_THRESHOLD) || hasAmount || hasQty;
        // **反事实的前提是"这是一张事件表"**：有主体、有对象、有时间，才谈得上"改变了输入→观察结果"。
        // 横截面表（IBM HR：一个员工一行、没有事件时间）即便有数值列也没有可学的关系 → 直接拒绝。
        boolean structuralCore = !p.of(ColumnRole.ENTITY_ID).isEmpty()
                && !p.of(ColumnRole.ITEM_ID).isEmpty()
                && !p.of(ColumnRole.EVENT_TIME).isEmpty();
        SchemaProposal.Capability capability;
        String reason;
        if (!structuralCore) {
            capability = SchemaProposal.Capability.NOT_CAPABLE;
            reason = "缺「主体 / 对象 / 时间」三件套（当前：主体=" + names(p.of(ColumnRole.ENTITY_ID))
                    + "，对象=" + names(p.of(ColumnRole.ITEM_ID))
                    + "，时间=" + names(p.of(ColumnRole.EVENT_TIME))
                    + "）→ 这不是事件表，没有「谁对什么在何时发生了什么」的行级关系，反事实无从谈起";
        } else if (hasLever && hasOutcome) {
            capability = SchemaProposal.Capability.CAPABLE;
            String leverDesc = !p.of(ColumnRole.LEVER).isEmpty()
                    ? names(p.of(ColumnRole.LEVER))
                    : names(p.of(ColumnRole.UNIT_PRICE)) + "（单价即杠杆）";
            reason = "表内有杠杆列（" + leverDesc + "）也有可观测结果 → 决策型，可直接做反事实";
        } else if (hasOutcome) {
            capability = SchemaProposal.Capability.IMPACT_ONLY;
            reason = "有可观测结果但没有真实杠杆列 → 冲击型：只能注入情景（如\"需求+10%\"），"
                    + "且必须声明注入方式的合理性边界";
        } else {
            capability = SchemaProposal.Capability.NOT_CAPABLE;
            reason = "既没有可操纵的杠杆列，也没有可观测的结果列 → 这是预测/描述任务，不是反事实任务，拒绝";
        }
        if (p.capability() != null && p.capability() != capability) {
            warnings.add("提案自称 " + p.capability() + "，校验器按角色算出来是 " + capability
                    + "（以校验器为准）：" + reason);
        }

        // ---------- ⑤ 数据检查：金额语义冲突（抽样真算，不靠猜） ----------
        if (hasAmount && hasQty && hasPrice) {
            if (file == null || !Files.isRegularFile(file)) {
                warnings.add("没有原始文件路径 → 跳过「数量×单价 vs 金额」的一致性抽样（该冲突未被检测）");
            } else {
                Consistency c = checkAmountConsistency(file, profile, p);
                if (c.rows > 0) {
                    warnings.add("金额一致性抽样：" + c.agree + "/" + c.rows + " 行满足 数量×单价≈金额（一致率 "
                            + String.format("%.4f", c.rate()) + "）");
                    if (c.rate() < CONSISTENCY_FLOOR) {
                        conflicts.add(new SchemaProposal.Unknown(
                                p.of(ColumnRole.AMOUNT).get(0).column(),
                                "金额列与 数量×单价 不一致（一致率 " + String.format("%.2f%%", c.rate() * 100)
                                        + "），请裁决用哪一个作为「金额」",
                                "不一致通常意味着该金额列是税后/成本/订单合计，直接当成交额会算错"));
                    }
                } else {
                    warnings.add("金额一致性抽样没有可比对的行（三列至少一列解析失败）");
                }
            }
        }
        // 有金额列也有单价列、但没有数量列 → 无法用数据判定，必须问人
        if (hasAmount && hasPrice && !hasQty) {
            conflicts.add(new SchemaProposal.Unknown(
                    p.of(ColumnRole.AMOUNT).get(0).column(),
                    "同时存在金额列与单价列，但没有数量列，无法用数据校验；确认金额列就是成交金额？",
                    "单价×数量与金额并存时，金额可能是别的口径（运费/税后/成本）"));
        }

        // ---------- ⑥ 低置信度 → 未决项（不许自动冻结） ----------
        for (RoleAssignment a : p.columns()) {
            if (a.lowConfidence(CONFIDENCE_THRESHOLD)) {
                conflicts.add(new SchemaProposal.Unknown(a.column(),
                        "置信度 " + String.format("%.2f", a.confidence()) + " 低于阈值 "
                                + CONFIDENCE_THRESHOLD + "，请确认角色 " + a.role() + " 是否正确",
                        "低置信度判定不允许自动冻结 —— 猜错的代价比多问一句大得多"));
            }
        }
        // 没被赋予任何角色的列 → 显式列出来（默认 IGNORE 也要人说一句）
        List<String> unassigned = profile.columns().stream()
                .map(ColumnProfile::name)
                .filter(n -> p.columns().stream().noneMatch(a -> a.column().equals(n)))
                .toList();
        if (!unassigned.isEmpty()) {
            warnings.add("未被赋予角色的列（默认不进模型）：" + String.join(", ", unassigned));
        }
        for (ColumnProfile c : profile.columns()) {
            if (c.flags().contains(ColumnProfile.PII_EMAIL) || c.flags().contains(ColumnProfile.PII_PHONE)) {
                RoleAssignment a = p.columns().stream().filter(x -> x.column().equals(c.name())).findFirst().orElse(null);
                if (a == null || a.role() != ColumnRole.PII) {
                    warnings.add("画像标记了疑似个人信息，但角色不是 PII：" + c.name()
                            + "（个人信息绝不进模型）");
                }
            }
        }
        return new Result(errors, warnings, conflicts, capability, reason);
    }

    /** 抽样比对 数量×单价 与 金额 的一致率。 */
    private Consistency checkAmountConsistency(Path file, DataProfile profile, SchemaProposal p) {
        int qi = index(profile, p.of(ColumnRole.QUANTITY).get(0).column());
        int pi = index(profile, p.of(ColumnRole.UNIT_PRICE).get(0).column());
        int ai = index(profile, p.of(ColumnRole.AMOUNT).get(0).column());
        if (qi < 0 || pi < 0 || ai < 0) {
            return new Consistency(0, 0);
        }
        Charset cs = Charset.forName(profile.charset());
        // 画像里的 delimiter 存的就是那一个字符本身（制表符就是真的制表符），取首字符即可 —— 
        // 不需要为制表符写特例（之前写了个 char 字面量 '\\t'，那是两个字符，编译直接报错）。
        char delimiter = profile.delimiter() == null || profile.delimiter().isEmpty()
                ? ',' : profile.delimiter().charAt(0);
        long[] stats = {0, 0};
        try (Reader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(Files.newInputStream(file), cs), 1 << 16)) {
            CsvTable table = CsvTable.open(r, delimiter);
            long[] seen = {0};
            table.forEach(row -> {
                if (seen[0] >= SAMPLE_ROWS) {
                    return;
                }
                seen[0]++;
                java.math.BigDecimal q = num(CsvTable.cell(row, qi));
                java.math.BigDecimal pr = num(CsvTable.cell(row, pi));
                java.math.BigDecimal a = num(CsvTable.cell(row, ai));
                if (q == null || pr == null || a == null) {
                    return;
                }
                stats[1]++;
                java.math.BigDecimal expected = q.abs().multiply(pr);
                java.math.BigDecimal diff = expected.subtract(a).abs();
                java.math.BigDecimal tol = a.abs().multiply(new java.math.BigDecimal("0.01")).max(new java.math.BigDecimal("0.01"));
                if (diff.compareTo(tol) <= 0) {
                    stats[0]++;
                }
            });
        } catch (IOException e) {
            log.warn("金额一致性抽样失败（不影响其它校验）: {}", e.getMessage());
            return new Consistency(0, 0);
        }
        return new Consistency(stats[0], stats[1]);
    }

    private record Consistency(long agree, long rows) {
        double rate() {
            return rows == 0 ? 0 : (double) agree / rows;
        }
    }

    private static java.math.BigDecimal num(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int index(DataProfile profile, String column) {
        for (int i = 0; i < profile.columns().size(); i++) {
            if (profile.columns().get(i).name().equals(column)) {
                return i;
            }
        }
        return -1;
    }

    private static String names(List<RoleAssignment> list) {
        return list.stream().map(RoleAssignment::column).collect(java.util.stream.Collectors.joining(", "));
    }

    private static void requireExactlyOne(SchemaProposal p, ColumnRole role, List<String> errors) {
        List<RoleAssignment> l = p.of(role);
        if (l.isEmpty()) {
            errors.add("缺少必需角色 " + role + "（" + role.description() + "）");
        } else if (l.size() > 1) {
            errors.add("角色 " + role + " 必须唯一，现在有 " + l.size() + " 个：" + names(l));
        }
    }

    private static void requireAtMostOne(SchemaProposal p, ColumnRole role, List<String> errors) {
        if (p.of(role).size() > 1) {
            errors.add("角色 " + role + " 最多一个，现在有 " + p.of(role).size() + " 个：" + names(p.of(role)));
        }
    }
}
