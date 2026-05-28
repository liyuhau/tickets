package org.common;

/**
 * 业务异常。可预期的业务错误时抛出本异常，
 * 由 {@link GlobalExceptionHandler} 统一捕获并转为 {@link R}。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BusinessException(ResultCode rc, String message) {
        super(message);
        this.code = rc.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() { return code; }
}
