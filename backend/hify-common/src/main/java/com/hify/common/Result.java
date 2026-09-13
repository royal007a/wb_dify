package com.hify.common;

public class Result<T> {
    private final int code;
    private final String message;
    private final T data;

    protected Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data);
    }

    public static Result<Void> ok() { return ok(null); }
    public static <T> Result<T> fail(ErrorCode errorCode) { return fail(errorCode, errorCode.message()); }
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null);
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}
