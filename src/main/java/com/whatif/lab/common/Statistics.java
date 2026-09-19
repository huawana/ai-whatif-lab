package com.whatif.lab.common;

/**
 * 统计工具：t 分布的 CDF / 分位数 / 双侧 p 值。
 *
 * <p><b>为什么必须老实实现 t 分布，而不是用正态近似</b>：
 * 仿真次数通常在 120~1000 这个量级。样本量小的时候，t 分布与正态分布的尾部差别很实在：
 * <pre>
 *   自由度 df=10  → 95% 双侧临界值 = 2.228（正态是 1.96，差 13%）
 *   自由度 df=119 → 95% 双侧临界值 = 1.980（仍有 1% 的差）
 *   自由度 df=∞   → 1.960
 * </pre>
 * 拿 1.96 去套 df=10 的样本，会把「其实不显著」的结论报成显著 —— 这是实打实的错误结论，
 * 不是精度问题。项目开头承诺了「带显著性」，就必须把这个数算对。
 *
 * <p>实现路线（教科书标准做法，全部可被数值反验）：
 * <pre>
 *   t 分布双侧 p 值  →  正则化不完全 Beta 函数：p = I_x(df/2, 1/2)，其中 x = df / (df + t²)
 *   不完全 Beta     →  连分式展开（Lentz 算法）+ logGamma（Lanczos 近似）
 *   t 分位数        →  对 p 值函数做二分（p 随 |t| 单调递减，二分必然收敛且不依赖初值）
 * </pre>
 * 二分而不是牛顿迭代，是刻意的：分位数只在算置信区间时用几十次，性能无所谓，
 * 但二分没有导数、不会发散、不会因为初值离谱而跑飞 —— 稳定性优先。
 */
public final class Statistics {

    private Statistics() {
    }

    /** log Γ(x)，Lanczos 近似（g=7, n=9）。 */
    public static double logGamma(double x) {
        double[] c = {
                0.99999999999980993, 676.5203681218851, -1259.1392167224028,
                771.32342877765313, -176.61502916214059, 12.507343278686905,
                -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            // 反射公式：Γ(x)Γ(1−x) = π / sin(πx)
            return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
        }
        double xx = x - 1;
        double a = c[0];
        double t = xx + 7.5;
        for (int i = 1; i < 9; i++) {
            a += c[i] / (xx + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (xx + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** 连分式部分（Numerical Recipes 的 betacf）。 */
    private static double betaContinuedFraction(double a, double b, double x) {
        double tiny = 1e-30;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1;
        double d = 1 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-12) {
                break;   // 已收敛
            }
        }
        return h;
    }

    /** 正则化不完全 Beta 函数 I_x(a,b)。 */
    public static double regularizedIncompleteBeta(double a, double b, double x) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaContinuedFraction(a, b, x) / a;
        }
        return 1 - front * betaContinuedFraction(b, a, 1 - x) / b;
    }

    /**
     * t 分布双侧 p 值：P(|T| >= |t|)，df 为自由度。
     *
     * <p>恒等式：P(|T| >= |t|) = I_{df/(df+t²)}(df/2, 1/2)。
     * 自检：t=0 → x=1 → I=1 → p=1（差值全零时不可能显著）；
     * t=2.228, df=10 → p≈0.05；t=1.96, df 很大 → p≈0.05。
     */
    public static double twoSidedPValue(double t, int df) {
        if (df <= 0) {
            return 1;
        }
        double tt = t * t;
        // 全零差值（se=0）会走到这里 t=0 → p=1，语义正确：没有差异就没有显著性
        double x = df / (df + tt);
        return regularizedIncompleteBeta(df / 2.0, 0.5, x);
    }

    /** t 分布双侧 1−alpha 置信度下的临界值，例如 conf=0.95 → t_{0.975, df}。 */
    public static double tQuantile(double conf, int df) {
        if (df <= 0) {
            return Double.NaN;
        }
        double target = 1 - conf;   // 双侧尾部概率
        double lo = 0;
        double hi = 100;
        // p(t) 随 |t| 严格单调递减 → 二分安全收敛
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (twoSidedPValue(mid, df) > target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** 标准正态 CDF（用于大样本兜底与自检对照）。 */
    public static double normalCdf(double z) {
        return 0.5 * (1 + erf(z / Math.sqrt(2)));
    }

    /** erf 近似（Abramowitz & Stegun 7.1.26），精度 ~1.5e-7。 */
    public static double erf(double x) {
        double sign = x < 0 ? -1 : 1;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return sign * y;
    }
}
