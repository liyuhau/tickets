package org.common.dto;

import lombok.Getter;

/**
 * API 统一返回码枚举。
 * <p>
 * 规范：
 * - 200: 成功
 * - 1xxx: 通用错误码
 * - 2xxx: 用户相关错误码
 * - 3xxx: 业务逻辑错误码 (订单、库存等)
 * - 4xxx: 中间件/下游服务错误码
 * - 5xxx: 系统/未知错误
 */
@Getter
public enum ResultCode {

    /* 成功 */
    SUCCESS(200, "Success"),

    /* 通用错误 */
    FAILURE(1000, "操作失败"),
    PARAM_VALIDATION_ERROR(1001, "参数校验失败"),
    RESOURCE_NOT_FOUND(1004, "资源未找到"),

    /* 认证与授权 */
    UNAUTHORIZED(1401, "未授权或认证已过期"),
    FORBIDDEN(1403, "无权访问"),

    /* 服务调用错误 */
    SERVICE_UNAVAILABLE(4001, "下游服务暂时不可用"),
    RPC_TIMEOUT(4002, "服务调用超时"),

    /* 系统错误 */
    INTERNAL_SERVER_ERROR(5000, "服务器内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
