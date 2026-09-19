package com.whatif.lab.ai.schema;

/**
 * 某一列的角色判定 —— <b>每个判定都必须带证据</b>。
 *
 * <p><b>为什么要强制带证据</b>：判断错了要能复盘"它凭什么这么判"。
 * 没有证据的判定 = 没法复盘 = 只能整体信任或整体不信任（这正是 LLM 判列最大的坑）。
 * 证据必须是**画像里能查到的事实或标准的语义推断**，
 * 例如"空值率 99.15% 且只在交易行有值"、"DECIMAL 非负、P95=6735"、"列名含 unitprice"。
 *
 * @param column     源列名
 * @param role       角色（闭集）
 * @param confidence 置信度 0~1。低于阈值会被移到 unknowns（不自动冻结）
 * @param evidence   证据（人可读，写清依据的是哪几个事实）
 * @param decider    谁判的：HEURISTIC / LLM / HUMAN —— 出问题时要能分清是规则错还是模型错
 */
public record RoleAssignment(String column,
                             ColumnRole role,
                             double confidence,
                             String evidence,
                             Decider decider) {

    public enum Decider { HEURISTIC, LLM, HUMAN }

    public boolean lowConfidence(double threshold) {
        return confidence < threshold;
    }
}
