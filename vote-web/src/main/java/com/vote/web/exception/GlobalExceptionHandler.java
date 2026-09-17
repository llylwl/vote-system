package com.vote.web.exception;

import com.vote.common.exception.BusinessException;
import com.vote.common.exception.RateLimitException;
import com.vote.common.result.ErrorCode;
import com.vote.common.result.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理
 * <p>
 * <b>相比早期实现的关键改进：把业务码映射为真实的 HTTP 状态码。</b>
 * 原实现所有异常都以 HTTP 200 返回，只在响应体里放业务码，导致：
 * 网关/WAF 无法识别限流、客户端重试库无法按状态码退避、
 * 监控系统统计到的错误率恒为 0。现在 429/400/404/403 都会如实反映在状态行上。
 *
 * @author hzp
 * @since 2026-9-13
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 限流：额外返回 Retry-After，便于客户端与网关退避 */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result<Void>> handleRateLimit(RateLimitException e) {
        log.warn("触发限流: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /** 业务异常：按错误码对应的 HTTP 状态返回 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        HttpStatus status = resolveStatus(e.getHttpStatus());
        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
    }

    /** @RequestBody 上的 @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = ErrorCode.BAD_REQUEST.getMessage();
        }
        log.warn("请求参数校验失败: {}", detail);
        return badRequest(detail);
    }

    /** 方法参数上的约束校验失败（如 @Validated + @Min） */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("请求参数校验失败: {}", detail);
        return badRequest(detail.isEmpty() ? ErrorCode.BAD_REQUEST.getMessage() : detail);
    }

    /** 缺少必填查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String detail = "缺少必填参数: " + e.getParameterName();
        log.warn(detail);
        return badRequest(detail);
    }

    /** 参数类型不匹配，如把非数字传给 Long 类型的路径变量 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String detail = "参数类型不正确: " + e.getName();
        log.warn(detail);
        return badRequest(detail);
    }

    /** 请求体无法解析（JSON 格式错误等） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return badRequest("请求体格式不正确，请检查 JSON 是否合法");
    }

    /** 请求路径不存在：必须返回 404，不能被兜底处理器转成 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("请求路径不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(ErrorCode.NOT_FOUND, "接口不存在: " + e.getResourcePath()));
    }

    /** 兜底：不向外暴露内部细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(ErrorCode.BAD_REQUEST, message));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private HttpStatus resolveStatus(int httpStatus) {
        HttpStatus status = HttpStatus.resolve(httpStatus);
        return status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }
}
