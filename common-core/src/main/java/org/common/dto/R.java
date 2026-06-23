package org.common.dto;

import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * API 统一返回结果封装。
 *
 * @param <T> 数据载荷的类型
 */
@Getter
@ToString
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String message;
    private final T data;

    private R(ResultCode resultCode, T data) {
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = data;
    }

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> success(T data) {
        return new R<>(ResultCode.SUCCESS, data);
    }

    public static <T> R<T> success() {
        return success(null);
    }

    public static <T> R<T> failure() {
        return new R<>(ResultCode.FAILURE, null);
    }

    public static <T> R<T> failure(ResultCode resultCode) {
        return new R<>(resultCode, null);
    }

    public static <T> R<T> failure(ResultCode resultCode, T data) {
        return new R<>(resultCode, data);
    }

    public static <T> R<T> failure(int code, String message) {
        return new R<>(code, message, null);
    }
}
