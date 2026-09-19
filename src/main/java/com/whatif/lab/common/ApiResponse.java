package com.whatif.lab.common;

/**
 * 统一响应包装。
 *
 * <p>为什么不用 HTTP 状态码区分业务错误：本项目的前端要做「AI 不可用 → 展示确定性结果 + 提示」
 * 这类细粒度分支，用 200 + code 更好表达 —— 调用方一眼能看出「是接口错了还是 AI 关了」。
 * 只有真正的协议级错误（参数不合法、找不到资源）才走 HTTP 4xx/5xx。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    /** 业务错误：HTTP 200，但 code != 0。 */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
