package org.inventory.api;

/**
 * Dubbo 跨进程业务异常。承载业务错误码 + 消息，
 * 调用方接到后可转换为自身的 BusinessException。
 */
public class InventoryRpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public InventoryRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() { return code; }
}
