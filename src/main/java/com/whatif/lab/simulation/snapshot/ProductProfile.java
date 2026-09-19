package com.whatif.lab.simulation.snapshot;

/**
 * 商品画像（由历史事件聚合而来）。
 *
 * <p>{@code avgUnitPrice} 是「挂牌价」的代理：UCI 数据里同一个商品在不同时间的成交价不同，
 * 这里取历史成交均价作为基线价格，仿真的价格规则作用在它之上。
 * {@code minPrice}/{@code maxPrice} 记录历史价格区间 —— 用于事后校验
 * 「Scenario 里的价格是否落在这个商品实际被观测过的价格区间内」，
 * 超出区间就是外推，结论必须打折扣（见 SimulationEngine 的外推比例统计）。
 */
public record ProductProfile(int index,
                             String externalId,
                             String description,
                             int purchaseLines,
                             long totalQuantity,
                             double avgUnitPrice,
                             double minPrice,
                             double maxPrice,
                             int typicalQuantity) {
}
