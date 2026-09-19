package com.whatif.lab.cache;

import com.whatif.lab.service.ScenarioService;

import java.util.List;

/**
 * 缓存键构造 —— 实验结果的「身份指纹」。
 *
 * <pre>
 *   SHA256(datasetVersion + modelVersion + ruleVersion + scenarioDSL + randomSeed + simulationCount
 *          + activeCustomers + candidatesPerCustomer + durationDays + grossMargin)
 * </pre>
 *
 * <p><b>为什么必须把所有参与计算的参数都放进哈希</b>：
 * 少放一个参数，就会出现「改了它但缓存命中，于是返回旧结果」的静默错误 ——
 * 这类 bug 极其难查，因为系统行为看起来完全正常，只有数字是错的（而且是上一次的）。
 * 所以这里的原则是：<b>凡是能改变结果的，一律进哈希</b>。
 * 毛利率看起来像「展示参数」，其实它直接决定利润指标，必须进。
 *
 * <p>哈希的对象是<b>归一化后的字符串</b>（见 {@link com.whatif.lab.domain.scenario.ScenarioDsl#canonicalForm()}）：
 * DSL 里 changes 的顺序、名字的空格、键的次序都不应影响「这是不是同一个实验」。
 * 不归一化的话，同一句自然语言被 LLM 生成两次就可能得到两个哈希，
 * 缓存与 SingleFlight 全部失守 —— 这是最隐蔽的一类缓存穿透。
 */
public final class CacheKeys {

    private static final String PREFIX = "whatif:";

    /**
     * 引擎版本 —— <b>必须参与指纹计算</b>。
     *
     * <p>为什么：指纹原本只包含「参数」（数据集/模型/规则/情景/种子/仿真次数）。
     * 于是会发生一件非常隐蔽的事：<b>改了引擎代码之后，同参数的实验命中旧缓存，
     * 直接返回用旧代码算出来的结果</b> —— 你会以为"修了但没生效"，
     * 甚至怀疑修复本身是错的。实测就踩到这个：修完一个特征变换 bug 后重跑，
     * 结果依然是修改前那份"GMV 全 0"的缓存。
     *
     * <p>规则很简单：<b>任何会改变计算结果的代码变更，都必须手工 bump 这个版本号。</b>
     * 它做不到自动失效（要做到得引入构建号或代码哈希），但它是显式的、
     * 且能被验证脚本断言（同参数两次跑必须命中；改了版本号必须不命中）。
     */
    public static final String ENGINE_VERSION = "engine-2026.09.19.4";

    private CacheKeys() {
    }

    public static String experimentResult(String fingerprint) {
        return PREFIX + "experiment:result:" + fingerprint;
    }

    public static String experimentStatus(Long experimentId) {
        return PREFIX + "experiment:" + experimentId + ":status";
    }

    /** 单飞（SingleFlight）锁：同一指纹只允许一个执行者，其他请求共享结果。 */
    public static String experimentLock(String fingerprint) {
        return PREFIX + "experiment:lock:" + fingerprint;
    }

    /** 进度通道（SSE 与状态查询共用）。 */
    public static String experimentProgress(Long experimentId) {
        return PREFIX + "experiment:" + experimentId + ":progress";
    }

    public static String datasetModelLock(Long datasetId) {
        return PREFIX + "dataset:" + datasetId + ":model-lock";
    }

    /**
     * 生成指纹。
     *
     * @param parts 所有会改变计算结果的参数（按固定顺序传入）
     */
    public static String fingerprint(List<Object> parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p == null ? "-" : String.valueOf(p)).append('|');
        }
        return ScenarioService.hash(sb.toString());
    }
}
