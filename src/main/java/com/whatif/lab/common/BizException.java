package com.whatif.lab.common;

/**
 * 业务异常。
 *
 * <p>{@link #code} 是给调用方做程序化分支用的（例如 AI_DISABLED 让前端走降级 UI），
 * 不是随手写的错误码。集中在这里定义，避免各处以字符串字面量判等。
 */
public class BizException extends RuntimeException {

    /** AI 未启用（没配 key 或总开关关闭）—— 这是**正常状态**，不是故障。 */
    public static final int AI_DISABLED = 1001;
    /** 参数/DSL 校验不通过。 */
    public static final int VALIDATION_FAILED = 1002;
    /** 资源不存在。 */
    public static final int NOT_FOUND = 1003;
    /** 状态冲突（例如实验已在运行）。 */
    public static final int CONFLICT = 1004;
    /** AI 调用失败（超时、限流、上游错误）。AI 失败不得影响确定性链路。 */
    public static final int AI_FAILED = 1005;
    /** 缓存/Redis 相关降级。 */
    public static final int CACHE_UNAVAILABLE = 1006;

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BizException notFound(String what) {
        return new BizException(NOT_FOUND, what + " 不存在");
    }

    public static BizException validation(String message) {
        return new BizException(VALIDATION_FAILED, message);
    }

    public static BizException aiDisabled(String reason) {
        return new BizException(AI_DISABLED, reason);
    }

    /**
     * AI 上游调用失败（超时 / 限流 / 账户欠费 / 上游 5xx）。
     *
     * <p><b>为什么不和「校验失败」共用一个错误码</b>：
     * 两者对调用方的含义完全不同 ——
     * <ul>
     *   <li>校验失败（1002）：模型正常返回了，但输出不合规。调用方可以改 Prompt / 重试 / 提示用户换个说法；</li>
     *   <li>上游失败（1005）：模型根本没被调用成功。重试、改 Prompt 都没用，
     *       该做的是<b>告警 + 降级到手工 DSL</b>（欠费要去充值，限流要等）。</li>
     * </ul>
     * 混在一起会让运维/Agent 对着"模型输出不合规"这一句错误信息排查半天，
     * 而真正的原因是账户欠费 —— 实测就踩到了这种情况（Arrearage）。
     */
    public static BizException aiFailed(String reason) {
        return new BizException(AI_FAILED, reason);
    }
}
