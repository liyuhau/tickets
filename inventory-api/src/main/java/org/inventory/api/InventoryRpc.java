package org.inventory.api;

/**
 * Dubbo 服务契约常量（version / scope-keys），
 * 提供方和消费方共用，避免双侧硬编码不一致。
 *
 * <p>真实业务运行时 timeout 等通过 yaml 覆盖：</p>
 * <pre>
 * dubbo:
 *   provider:
 *     timeout: 3000
 *   consumer:
 *     timeout: 3000
 *     check: false
 * </pre>
 */
public final class InventoryRpc {

    public static final String VERSION = "1.0.0";

    /** OAuth2 scope 名 */
    public static final String SCOPE_INVENTORY_READ  = "inventory.read";
    public static final String SCOPE_BOOKING_READ    = "booking.read";
    public static final String SCOPE_BOOKING_WRITE   = "booking.write";

    private InventoryRpc() {}
}
