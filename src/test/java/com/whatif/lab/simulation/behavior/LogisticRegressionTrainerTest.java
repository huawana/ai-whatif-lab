package com.whatif.lab.simulation.behavior;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估指标的自检。
 *
 * <p><b>这个测试类的直接来源是一次真事故</b>：AUC 的排序方向写反，
 * 让一个真实 AUC≈0.86 的模型被报成 0.14（"比瞎猜还差"），
 * 第一反应会去怀疑数据/标签/特征，而问题只在评估函数里。
 * 所以这里最关键的一条不是"数值对不对"，而是
 * <b>"把好模型喂进去，评估指标必须给出好分数"</b> —— 方向性回归测试。
 */
class LogisticRegressionTrainerTest {

    private static double[] scores(double... v) {
        return v;
    }

    @Test
    @DisplayName("方向性回归：正样本分数高 → AUC 必须 > 0.5（这条能抓住排序方向写反的 bug）")
    void aucMustBeAboveHalfWhenModelIsGood() {
        // 正样本分数普遍更高（一个"好模型"的输出）
        double[] s = scores(0.9, 0.8, 0.85, 0.7, 0.2, 0.15, 0.3, 0.1);
        int[] y = {1, 1, 1, 1, 0, 0, 0, 0};
        double auc = LogisticRegressionTrainer.auc(s, y);
        assertTrue(auc > 0.5, "好模型的 AUC 必须 > 0.5，实际 " + auc + "（若得到 1-AUC，说明排序方向反了）");
        assertEquals(1.0, auc, 1e-9, "完全可分时 AUC 必须恰好为 1");
    }

    @Test
    @DisplayName("完美反向 → AUC = 0（对称性自检）")
    void aucSymmetry() {
        double[] s = scores(0.1, 0.2, 0.3, 0.9, 0.8, 0.7);
        int[] y = {1, 1, 1, 0, 0, 0};
        assertEquals(0.0, LogisticRegressionTrainer.auc(s, y), 1e-9);
    }

    @Test
    @DisplayName("随机分数 → AUC ≈ 0.5")
    void aucOnRandomScores() {
        Random rng = new Random(42);
        int n = 20000;
        double[] s = new double[n];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            s[i] = rng.nextDouble();
            y[i] = rng.nextDouble() < 0.3 ? 1 : 0;
        }
        double auc = LogisticRegressionTrainer.auc(s, y);
        assertEquals(0.5, auc, 0.02, "纯随机分数 AUC 应在 0.5 附近，实际 " + auc);
    }

    @Test
    @DisplayName("并列分数取平均秩 → AUC 不被并列拉偏")
    void aucHandlesTies() {
        // 所有分数相同：正负完全不可分 → AUC 必须恰好 0.5
        double[] s = scores(0.5, 0.5, 0.5, 0.5);
        int[] y = {1, 1, 0, 0};
        assertEquals(0.5, LogisticRegressionTrainer.auc(s, y), 1e-9);
        // 只有一个正样本、一个负样本且相等 → 同样 0.5
        assertEquals(0.5, LogisticRegressionTrainer.auc(scores(0.3, 0.3), new int[]{1, 0}), 1e-9);
    }

    @Test
    @DisplayName("sigmoid 数值稳定：极端输入不产生 NaN")
    void sigmoidIsNumericallyStable() {
        assertEquals(0.0, BehaviorModel.sigmoid(-1000), 1e-12);
        assertEquals(1.0, BehaviorModel.sigmoid(1000), 1e-12);
        assertEquals(0.5, BehaviorModel.sigmoid(0), 1e-12);
        assertTrue(!Double.isNaN(BehaviorModel.sigmoid(800)));
    }

    @Test
    @DisplayName("训练是确定性的：同数据同配置 → 权重逐位相同（实验可复现的地基）")
    void trainingIsDeterministic() {
        Random rng = new Random(7);
        int n = 2000;
        double[][] x = new double[n][3];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            double a = rng.nextGaussian();
            double b = rng.nextGaussian();
            double c = rng.nextGaussian();
            x[i] = new double[]{a, b, c};
            // 让 a 与标签强正相关、b 弱负相关，c 是噪声
            double z = 1.5 * a - 0.4 * b;
            y[i] = rng.nextDouble() < 1.0 / (1.0 + Math.exp(-z)) ? 1 : 0;
        }
        var cfg = new LogisticRegressionTrainer.Config(0.5, 200, 1e-4);
        var r1 = LogisticRegressionTrainer.fit(x, y, cfg);
        var r2 = LogisticRegressionTrainer.fit(x, y, cfg);
        for (int i = 0; i < r1.coefficients().length; i++) {
            assertEquals(r1.coefficients()[i], r2.coefficients()[i], 0.0, "系数必须逐位相同");
        }
        assertEquals(r1.intercept(), r2.intercept(), 0.0, "截距必须逐位相同");
        // 并且学出来的方向应该是"a 的系数明显大于 b"（恢复出构造数据时的规律）
        assertTrue(r1.coefficients()[0] > r1.coefficients()[1],
                "强相关特征的系数应大于弱相关特征");
        assertTrue(r1.coefficients()[0] > 0, "正相关特征的系数应为正");
        assertTrue(r1.coefficients()[1] < 0, "负相关特征的系数应为负");
    }

    @Test
    @DisplayName("标准化参数被正确落库：常数列（std=0）不能除零")
    void standardizationHandlesConstantColumn() {
        double[][] x = new double[10][2];
        int[] y = new int[10];
        for (int i = 0; i < 10; i++) {
            x[i] = new double[]{i, 5.0};   // 第二列是常量
            y[i] = i % 2;
        }
        var r = LogisticRegressionTrainer.fit(x, y, LogisticRegressionTrainer.Config.defaults());
        assertTrue(!Double.isNaN(r.coefficients()[1]), "常量列的系数不能是 NaN");
        assertEquals(1.0, r.stds()[1], 1e-9, "常量列的标准差应被兜底为 1");
    }
}
