package com.vote.common.exception;

import lombok.Getter;

/**
 * 业务异常
 * @author hzp
 * @since 2026-9-15
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(500, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
