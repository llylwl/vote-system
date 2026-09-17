package com.vote.common.exception;

import com.vote.common.result.ErrorCode;

/**
 * 限流异常
 *
 * @author hzp
 * @since 2026-9-13
 */
public class RateLimitException extends BusinessException {

    public RateLimitException(String message) {
        super(ErrorCode.RATE_LIMITED, message);
    }

    public RateLimitException() {
        super(ErrorCode.RATE_LIMITED);
    }
}
