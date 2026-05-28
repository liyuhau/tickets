package org.common;

/**
 * 统一业务状态码。
 * <ul>
 *   <li>0          —— 成功</li>
 *   <li>1xxx       —— 通用错误（参数、签名等）</li>
 *   <li>2xxx       —— 业务错误（库存、订单等）</li>
 *   <li>5xxx       —— 系统/服务端错误</li>
 * </ul>
 */
public enum ResultCode {

    SUCCESS(0, "ok"),

    // 通用
    BAD_REQUEST(1001, "请求参数错误"),
    NOT_FOUND(1002, "资源不存在"),
    METHOD_NOT_ALLOWED(1003, "请求方法不被允许"),
    UNAUTHORIZED(1401, "未登录或登录已过期"),
    FORBIDDEN(1403, "无权访问"),
    TOO_MANY_REQUESTS(1429, "请求过于频繁，请稍后再试"),

    // 业务
    INSUFFICIENT_STOCK(2001, "库存不足"),
    PRODUCT_NOT_FOUND(2002, "商品不存在"),
    ORDER_NOT_FOUND(2003, "订单不存在"),
    REMOTE_CALL_FAILED(2999, "下游服务调用失败"),

    // 系统
    INTERNAL_ERROR(5000, "服务内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
