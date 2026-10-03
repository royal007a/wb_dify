package com.hify.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> malformedJson(HttpMessageNotReadableException exception) {
        // Jackson messages may contain submitted secrets: do not log or return them.
        return fail(ErrorCode.PARAM_ERROR, "请求 JSON 格式或字段类型不正确");
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> business(BizException exception) {
        return fail(exception.errorCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.PARAM_ERROR.message());
        return fail(ErrorCode.PARAM_ERROR, message);
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    public ResponseEntity<Result<Void>> validation(Exception exception) {
        return fail(ErrorCode.PARAM_ERROR, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<Void>> conflict(IllegalStateException exception) {
        ErrorCode code = "IDEMPOTENCY_KEY_REUSED".equals(exception.getMessage())
                ? ErrorCode.IDEMPOTENCY_KEY_REUSED : ErrorCode.CONFLICT;
        return fail(code, exception.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> notFound(NoResourceFoundException exception) {
        return fail(ErrorCode.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception exception) {
        log.error("Unhandled request failure", exception);
        return fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
    }

    private ResponseEntity<Result<Void>> fail(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(Result.fail(code, message));
    }
}
