package com.hify.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    OK(200, "success", HttpStatus.OK),
    PARAM_ERROR(40000, "参数错误", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "未授权", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "无权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(40400, "资源不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(40500, "不支持此请求方法", HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(40600, "不支持此响应格式", HttpStatus.NOT_ACCEPTABLE),
    IDEMPOTENCY_KEY_REUSED(40901, "幂等键已被不同请求使用", HttpStatus.CONFLICT),
    CONFLICT(40900, "资源状态冲突", HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE(41500, "不支持此请求内容类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_ERROR(50000, "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(int code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public int code() { return code; }
    public String message() { return message; }
    public HttpStatus status() { return status; }
}
