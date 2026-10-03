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
import org.springframework.http.MediaType;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<Result<Void>> requestBinding(Exception exception) {
        // Framework exception messages can contain the rejected value, including credentials.
        return fail(ErrorCode.PARAM_ERROR, ErrorCode.PARAM_ERROR.message());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return fail(ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.message());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> malformedMultipart(MultipartException exception) {
        // Parser diagnostics can contain a submitted filename, boundary or body.
        return fail(ErrorCode.PARAM_ERROR, ErrorCode.PARAM_ERROR.message());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.status()).headers(exception.getHeaders())
                .contentType(MediaType.APPLICATION_JSON).body(Result.fail(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> unsupportedMedia(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status()).headers(exception.getHeaders())
                .contentType(MediaType.APPLICATION_JSON).body(Result.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Result<Void>> notAcceptable(HttpMediaTypeNotAcceptableException exception) {
        return fail(ErrorCode.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE.message());
    }

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
        return fail(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.message());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unexpected(Exception exception) {
        log.error("Unhandled request failure", exception);
        return fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
    }

    private ResponseEntity<Result<Void>> fail(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON).body(Result.fail(code, message));
    }
}
