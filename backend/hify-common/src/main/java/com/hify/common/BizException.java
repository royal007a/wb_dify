package com.hify.common;

public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
