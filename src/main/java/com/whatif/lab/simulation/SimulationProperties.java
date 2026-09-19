package com.whatif.lab.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 仿真引擎参数。
 *
 * <p>全部用 {@code @DefaultValue} 给默认值，而不是依赖 application.properties 里一定写了 ——
 * 少一个配置项不该让实验跑不起来（用默认值跑出来的结果也是可复现的，因为默认值本身是常量）。
 */
@ConfigurationProperties(prefix = "whatif.simulation")
public record SimulationProperties(

        /** 默认蒙特卡洛重复次数。 */
        @DefaultValue("200") int defaultSimulations,

        /** 单次实验允许的最大重复次数（防「有人传 100000」把服务器跑满）。 */
        @DefaultValue("2000") int maxSimulations,

        /** 默认模拟周期（天）。 */
        @DefaultValue("30") int defaultDurationDays,

        /** 每次仿真抽样的活跃客户数。 */
        @DefaultValue("1000") int activeCustomers,

        @DefaultValue("20000") int maxActiveCustomers,

        /** 每个客户每次购物会话考虑的商品数（负采样口径，训练/推理共用）。 */
        @DefaultValue("8") int candidatesPerCustomer,

        /** 客户平均多少天逛一次店 → 决定实验周期内的会话数。 */
        @DefaultValue("7") int sessionIntervalDays,

        /** 毛利率假设（成本 = 原价 × (1 − 毛利率)）。 */
        @DefaultValue("0.35") double grossMargin,

        /** 训练集负采样比例：1 个正样本配 N 个负样本。 */
        @DefaultValue("4") int negativeRatio,

        /** 并行线程数；0 = 串行（结果与并行无关，见 SimulationEngine 的说明）。 */
        @DefaultValue("0") int parallelism) {
}
