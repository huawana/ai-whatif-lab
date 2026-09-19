package com.whatif.lab.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统计工具的自检 —— 这个类存在的理由很直接：
 * <b>如果 p 值算错了，整套"带显著性"的结论就全是假的</b>，而且错得很隐蔽
 * （不会抛异常，只会给出一个看着合理的数字）。
 *
 * <p>所以这里用两条独立的证据链来钉住它：
 * <ol>
 *   <li><b>教科书临界值</b>：t=2.228/df=10 → p≈0.05 这类公开数值，是外部权威来源；</li>
 *   <li><b>数值积分交叉验证</b>：直接在测试里对 t 分布密度函数做辛普森积分算出 p 值，
 *       与"不完全 Beta 函数"这条路线的结果比对。
 *       两条路线一条是解析近似（Lanczos + 连分式），一条是数值积分，
 *       数学工具完全不同 —— 它们给出同一个数，才叫验证。</li>
 * </ol>
 */
class StatisticsTest {

    /** t 分布密度函数（用于数值积分交叉验证）。 */
    private static double tDensity(double t, int df) {
        double logConst = Statistics.logGamma((df + 1) / 2.0) - Statistics.logGamma(df / 2.0)
                - 0.5 * Math.log(df * Math.PI);
        return Math.exp(logConst - (df + 1) / 2.0 * Math.log(1 + t * t / df));
    }

    /** 用辛普森法对 t 密度做数值积分，得到双侧 p 值（完全独立的第二条实现路线）。 */
    private static double pByNumericIntegration(double t, int df) {
        double upper = Math.abs(t) + 60;   // 尾部已可忽略
        int n = 200000;                    // 必须偶数
        double h = (upper - Math.abs(t)) / n;
        double sum = tDensity(Math.abs(t), df) + tDensity(upper, df);
        for (int i = 1; i < n; i++) {
            double x = Math.abs(t) + i * h;
            sum += tDensity(x, df) * (i % 2 == 1 ? 4 : 2);
        }
        double oneSided = h / 3 * sum;
        return Math.min(2 * oneSided, 1.0);
    }

    @Test
    @DisplayName("双侧 p 值：对教科书临界值（外部权威来源）")
    void pValueMatchesPublishedCriticalValues() {
        assertEquals(1.0, Statistics.twoSidedPValue(0, 10), 1e-9, "t=0 必然 p=1");

        // 公开 t 表：df=10 时双侧 0.05 对应 2.228，双侧 0.10 对应 1.812
        assertEquals(0.05, Statistics.twoSidedPValue(2.228, 10), 2e-3, "df=10, t=2.228 → p≈0.05");
        assertEquals(0.10, Statistics.twoSidedPValue(1.812, 10), 3e-3, "df=10, t=1.812 → p≈0.10");
        // df=30
        assertEquals(0.05, Statistics.twoSidedPValue(2.042, 30), 2e-3, "df=30, t=2.042 → p≈0.05");
        // 大自由度应收敛到正态：1.96 → 0.05
        assertEquals(0.05, Statistics.twoSidedPValue(1.960, 1_000_000), 1e-3, "df→∞, t=1.96 → p≈0.05");
    }

    @Test
    @DisplayName("双侧 p 值：与数值积分（独立实现）交叉验证")
    void pValueMatchesNumericIntegration() {
        int[] dfs = {5, 10, 30, 119, 500};
        double[] ts = {0.5, 1.0, 1.96, 2.5, 4.0};
        double maxDiff = 0;
        for (int df : dfs) {
            for (double t : ts) {
                double analytic = Statistics.twoSidedPValue(t, df);
                double numeric = pByNumericIntegration(t, df);
                maxDiff = Math.max(maxDiff, Math.abs(analytic - numeric));
                assertEquals(numeric, analytic, 1e-4,
                        "df=" + df + " t=" + t + " 两条实现路线必须一致");
            }
        }
        // 把最大偏差留个记录：如果这里忽然变大，说明有人改了实现
        assertTrue(maxDiff < 1e-4, "解析解与数值积分的最大偏差应 < 1e-4，实际 " + maxDiff);
    }

    @Test
    @DisplayName("t 分位数与 p 值互为逆运算（95% 置信区间要用它）")
    void quantileIsInverseOfPValue() {
        for (int df : new int[]{2, 5, 10, 30, 119, 1000}) {
            double tCrit = Statistics.tQuantile(0.95, df);
            assertEquals(0.05, Statistics.twoSidedPValue(tCrit, df), 1e-6,
                    "df=" + df + "：p(tQuantile(0.95)) 必须等于 0.05");
            // 小自由度时临界值必须明显大于正态的 1.96 —— 这正是"不能用正态近似"的理由
            if (df <= 10) {
                assertTrue(tCrit > 2.2, "df=" + df + " 的 95% 临界值应明显大于 1.96，实际 " + tCrit);
            }
        }
    }

    @Test
    @DisplayName("logGamma 自检：Γ(5)=24, Γ(0.5)=√π")
    void logGammaKnownValues() {
        assertEquals(Math.log(24), Statistics.logGamma(5), 1e-9);
        assertEquals(Math.log(Math.sqrt(Math.PI)), Statistics.logGamma(0.5), 1e-9);
    }

    @Test
    @DisplayName("标准正态 CDF 自检")
    void normalCdfKnownValues() {
        assertEquals(0.5, Statistics.normalCdf(0), 1e-7);
        assertEquals(0.975, Statistics.normalCdf(1.96), 2e-3);
        assertEquals(0.025, Statistics.normalCdf(-1.96), 2e-3);
    }
}
