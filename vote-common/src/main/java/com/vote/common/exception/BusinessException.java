package com.vote.common.exception;

import com.vote.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 推荐使用 {@link ErrorCode} 构造，避免业务码散落成魔法数字。
 * 保留按 code/message 构造的方式以兼容既有调用。
 *
 * @author hzp
 * @since 2026-9-15
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /** 对应的 HTTP 状态码，由全局异常处理器写入响应 */
    private final int httpStatus;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.httpStatus = errorCode.getHttpStatus();
    }

    public BusinessException(String message) {
        this(ErrorCode.INTERNAL_ERROR, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        ErrorCode matched = ErrorCode.fromCode(code);
        this.httpStatus = matched == null ? 500 : matched.getHttpStatus();
    }
}
