package com.vote.common.exception;

/**
 * 限流异常
 * @author hzp
 * @since 2026-9-15
 */
public class RateLimitException extends BusinessException {

    public RateLimitException(String message) {
        super(429, message);
    }
}
