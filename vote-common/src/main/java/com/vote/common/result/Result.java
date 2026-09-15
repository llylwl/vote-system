package com.vote.common.result;

import lombok.Data;

/**
 * 统一响应体
 * @author hzp
 * @since 2026-9-15
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(ErrorCode.SUCCESS.getCode());
        r.setMessage(ErrorCode.SUCCESS.getMessage());
        r.setData(data);
        return r;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    /** 按统一错误码构造失败响应 */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    /** 按统一错误码构造失败响应，并覆盖提示文案 */
    public static <T> Result<T> error(ErrorCode errorCode, String message) {
        return error(errorCode.getCode(), message);
    }
}
