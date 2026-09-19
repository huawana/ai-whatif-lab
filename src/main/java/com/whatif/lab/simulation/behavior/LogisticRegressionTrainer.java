package com.whatif.lab.simulation.behavior;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 逻辑回归训练器（全批量梯度下降 + L2）。
 *
 * <p><b>为什么自己写 20 行而不是引 Spark ML / Smile / Weka</b>：
 * ① 目标是「能被完整讲清楚」的模型 —— 面试官会问「梯度怎么算、为什么标准化、正则化干嘛的」，
 *    自己写的实现每个数字都答得上来；
 * ② 确定性：这些库默认会做随机采样/打乱，复现实验要多传一堆参数，
 *    全批量梯度下降在固定迭代数下**天然确定性** —— 同样的输入永远得到同样的权重。
 *
 * <p>三个必须做对的细节：
 * <ol>
 *   <li><b>标准化</b>：7 个特征量纲差 4 个数量级（价格 ln 后约 2，消费额 ln 后约 9，
 *       折扣率是 0~0.9）。不标准化会让不同特征的有效学习率差几个量级，梯度下降几乎不收敛。
 *       标准化的均值/方差必须落库，推理时用同一套参数 —— 否则训练/推理口径不一致。</li>
 *   <li><b>L2 正则</b>：特征之间有相关性（客户频次与消费额高度相关），
 *       不惩罚会导致权重互相抵消、数值巨大，反事实外推时直接爆炸。</li>
 *   <li><b>不标准化截距</b>：截距是在标准化空间里学的，保留原值即可 ——
 *       把截距也做标准化变换是最常见的实现 bug。</li>
 * </ol>
 */
public final class LogisticRegressionTrainer {

    public record Config(double learningRate, int epochs, double l2) {
        public static Config defaults() {
            return new Config(0.5, 600, 1e-4);
        }
    }

    public record Result(double[] coefficients,
                         double intercept,
                         double[] means,
                         double[] stds,
                         Map<String, Object> metrics) {
    }

    private LogisticRegressionTrainer() {
    }

    /**
     * 训练。
     *
     * @param x 原始特征矩阵（行 = 样本，列 = 特征）
     * @param y 标签（0/1）
     */
    public static Result fit(double[][] x, int[] y, Config config) {
        int n = x.length;
        if (n == 0) {
            throw new IllegalArgumentException("训练样本为空");
        }
        int d = x[0].length;

        double[] means = new double[d];
        double[] stds = new double[d];
        for (int j = 0; j < d; j++) {
            double sum = 0;
            for (double[] row : x) {
                sum += row[j];
            }
            double mean = sum / n;
            double sq = 0;
            for (double[] row : x) {
                double diff = row[j] - mean;
                sq += diff * diff;
            }
            means[j] = mean;
            double std = Math.sqrt(sq / Math.max(n - 1, 1));
            stds[j] = std < 1e-9 ? 1.0 : std;   // 常数列：std=0 会导致除零
        }

        double[][] xs = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                xs[i][j] = (x[i][j] - means[j]) / stds[j];
            }
        }

        double[] w = new double[d];
        double intercept = 0;
        double bestLoss = Double.MAX_VALUE;
        int stagnant = 0;

        for (int epoch = 0; epoch < config.epochs(); epoch++) {
            double[] grad = new double[d];
            double gradB = 0;
            double loss = 0;
            for (int i = 0; i < n; i++) {
                double z = intercept;
                for (int j = 0; j < d; j++) {
                    z += w[j] * xs[i][j];
                }
                double p = BehaviorModel.sigmoid(z);
                double err = p - y[i];
                for (int j = 0; j < d; j++) {
                    grad[j] += err * xs[i][j];
                }
                gradB += err;
                double pc = Math.min(Math.max(p, 1e-12), 1 - 1e-12);
                loss += -(y[i] * Math.log(pc) + (1 - y[i]) * Math.log(1 - pc));
            }
            for (int j = 0; j < d; j++) {
                grad[j] = grad[j] / n + config.l2() * w[j];
                w[j] -= config.learningRate() * grad[j];
            }
            intercept -= config.learningRate() * (gradB / n);

            loss /= n;
            if (loss < bestLoss - 1e-9) {
                bestLoss = loss;
                stagnant = 0;
            } else if (++stagnant > 25) {
                // 已收敛：继续迭代只会浪费时间，且更可能过拟合
                break;
            }
        }

        Map<String, Object> metrics = evaluate(xs, y, w, intercept, n);
        metrics.put("epochsRun", config.epochs());
        metrics.put("learningRate", config.learningRate());
        metrics.put("l2", config.l2());
        metrics.put("logLoss", round(bestLoss, 6));
        return new Result(w, intercept, means, stds, metrics);
    }

    /**
     * 评估指标。
     *
     * <p>为什么一定要看 <b>AUC</b> 而不是准确率：负样本是负采样出来的，
     * 正负比例由 {@code negative-ratio} 人为设定（1:4），准确率会随这个比例漂移 ——
     * 90% 的准确率在这里毫无意义。AUC 只看排序，与正负比例无关，是唯一可比的指标。
     */
    private static Map<String, Object> evaluate(double[][] xs, int[] y, double[] w, double intercept, int n) {
        double[] scores = new double[n];
        double brier = 0;
        int correct = 0;
        int positives = 0;
        for (int i = 0; i < n; i++) {
            double z = intercept;
            for (int j = 0; j < w.length; j++) {
                z += w[j] * xs[i][j];
            }
            double p = BehaviorModel.sigmoid(z);
            scores[i] = p;
            brier += (p - y[i]) * (p - y[i]);
            if ((p >= 0.5 ? 1 : 0) == y[i]) {
                correct++;
            }
            if (y[i] == 1) {
                positives++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("samples", n);
        m.put("positives", positives);
        m.put("positiveRate", round((double) positives / n, 6));
        m.put("auc", round(auc(scores, y), 6));
        m.put("accuracy", round((double) correct / n, 6));
        m.put("brier", round(brier / n, 6));
        m.put("predictedMean", round(mean(scores), 6));
        return m;
    }

    /**
     * AUC = P(随机取一个正样本的分数 > 随机取一个负样本的分数)。
     *
     * <p>用「秩和」公式算，复杂度 O(n log n)，同时对并列分数取平均秩 ——
     * 并列处理不对是最常见的 AUC 实现 bug（在有大量相同预测值的模型上误差可观）。
     *
     * <p><b>【实测踩到的坑，代价是一次误判】排序方向必须是升序。</b>
     * Mann-Whitney 秩和公式 {@code AUC = (R_pos − n_pos(n_pos+1)/2) / (n_pos × n_neg)}
     * 推导时假定「最小分数的秩 = 1」。我最初按分数<b>降序</b>排（最小分数拿最大秩），
     * 代入同一个公式，得到的结果恰好是 <b>1 − AUC</b>：
     * <pre>
     *   真实 AUC 0.86 → 报出来的 AUC = 0.14
     * </pre>
     * 0.14 的读法是「模型比瞎猜还差」，于是第一反应会去怀疑数据、怀疑标签、怀疑特征 ——
     * 而真正的错误只是评估函数里的一个 sort comparator 方向。
     * 这个 bug 之所以能被抓住，是因为「AUC=0.14」与「1−AUC=0.86」这个巧合太整齐了，
     * 顺藤摸瓜才查到了实现。**评估指标的实现必须被反向验证**，否则它会把好模型报成坏模型。
     */
    public static double auc(double[] scores, int[] y) {
        int n = scores.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        // 升序：秩从最小分数开始数 1,2,3...（这是下面秩和公式成立的前提）
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(scores[a], scores[b]));

        double rankSumPositive = 0;
        int rank = 1;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && scores[idx[j + 1]] == scores[idx[i]]) {
                j++;
            }
            double avgRank = (rank + (rank + (j - i))) / 2.0;   // 并列取平均秩
            for (int k = i; k <= j; k++) {
                if (y[idx[k]] == 1) {
                    rankSumPositive += avgRank;
                }
            }
            rank += (j - i + 1);
            i = j + 1;
        }
        long pos = 0;
        long neg = 0;
        for (int v : y) {
            if (v == 1) {
                pos++;
            } else {
                neg++;
            }
        }
        if (pos == 0 || neg == 0) {
            return 0.5;   // 只有一类样本时 AUC 无定义
        }
        // Mann-Whitney U 公式：AUC = (R_pos − n_pos(n_pos+1)/2) / (n_pos × n_neg)
        return (rankSumPositive - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double d : v) {
            s += d;
        }
        return s / v.length;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
