package com.whatif.lab.simulation.snapshot;

import java.time.LocalDateTime;

/**
 * 客户画像（由历史事件聚合而来，与规则无关）。
 *
 * <p>「与规则无关」这一点很关键：画像里只有客户自己的历史行为，
 * 不含任何价格/优惠信息。规则变化只通过 {@code 有效单价} 与 {@code 感知折扣率}
 * 两个入口影响预测概率，这样「规则 → 行为」的因果链是干净可解释的。
 */
public record CustomerProfile(int index,
                              String externalId,
                              String group,
                              int purchaseLines,
                              long totalQuantity,
                              double totalSpend,
                              double avgLineAmount,
                              LocalDateTime firstSeen,
                              LocalDateTime lastSeen) {
}
