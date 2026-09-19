package com.whatif.lab.data.profile;

import java.util.List;

/**
 * 一份 CSV 的完整客观画像 —— 这是 <b>AI Schema Mapper 的唯一输入</b>。
 *
 * <p><b>为什么 AI 只看画像、不看原始表</b>：
 * <ol>
 *   <li>隐私：真实业务表里有手机号、邮箱、身份证。画像只带"样例值 + 统计量"，可以按需脱敏；</li>
 *   <li>成本：54 万行不可能整表发给模型；画像与行数无关，永远是小体积；</li>
 *   <li>可复现：画像本身落库并带哈希，于是"AI 当时看到的是什么"永远可查 ——
 *       否则同一个 CSV 两次判出不同结果时，你无法判断是模型不稳定还是输入变了。</li>
 * </ol>
 *
 * @param charsetCandidates 所有能成功解码的候选字符集（顺序即优先级）
 * @param rowCount          全表精确行数；@param statsRows 参与统计的行数（大表只统计前 N 行，避免无界内存）
 * @param reservoirSeed     分位数采样种子（固定 → 结果可复现）
 */
public record DataProfile(
        String profileId,
        String profileVersion,
        String generatedAt,
        String fileName,
        String fileHash16,
        long sizeBytes,
        String charset,
        /** 检测到的分隔符（逗号/分号/制表符/竖线）。导入时必须用同一个，否则列会全错。 */
        String delimiter,
        List<String> charsetCandidates,
        int columnCount,
        long rowCount,
        long statsRows,
        int statsRowLimit,
        int reservoirSeed,
        List<ColumnProfile> columns,
        List<String> warnings) {
}
