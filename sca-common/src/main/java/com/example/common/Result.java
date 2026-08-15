package com.example.common;

/**
 * 统一返回包装（新接口成功响应）。
 * code = 0 表示成功；错误响应仍由全局异常处理器输出 ProblemDetail，本类不参与。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "success", data);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
