package com.whatif.lab.simulation;

import com.whatif.lab.domain.metric.MetricType;

/**
 * 一次仿真的指标向量（单个样本）。
 *
 * <p>每个仿真的结果都单独留下，而不是边跑边累加均值 ——
 * 因为要的不仅是均值，还有 P5/P50/P95 与配对差值的显著性，
 * 那些都必须拿到<b>逐个仿真的样本</b>才能算。
 */
public record ArmMetrics(double gmv,
                        double grossRevenue,
                        int orders,
                        int sessions,
                        double discountCost,
                        double cogs) {

    public double conversion() {
        return sessions == 0 ? 0 : (double) orders / sessions;
    }

    public double profit() {
        return gmv - cogs;
    }

    public double aov() {
        return orders == 0 ? 0 : gmv / orders;
    }

    public double valueOf(MetricType type) {
        return switch (type) {
            case GMV -> gmv;
            case GROSS_REVENUE -> grossRevenue;
            case ORDERS -> orders;
            case CONVERSION -> conversion();
            case DISCOUNT_COST -> discountCost;
            case PROFIT -> profit();
            case AOV -> aov();
        };
    }

    public static final ArmMetrics ZERO = new ArmMetrics(0, 0, 0, 0, 0, 0);

    public ArmMetrics plus(ArmMetrics o) {
        return new ArmMetrics(gmv + o.gmv, grossRevenue + o.grossRevenue, orders + o.orders,
                sessions + o.sessions, discountCost + o.discountCost, cogs + o.cogs);
    }
}
