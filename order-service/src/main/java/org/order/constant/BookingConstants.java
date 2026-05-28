package org.order.constant;

/**
 * 预订单业务字典常量（避免散落字符串硬编码）。
 */
public final class BookingConstants {

    /** 预订单状态机 */
    public static final class Status {
        public static final String CREATED   = "CREATED";
        public static final String PAID      = "PAID";
        public static final String CANCELLED = "CANCELLED";
        public static final String REFUNDED  = "REFUNDED";
        private Status() {}
    }

    private BookingConstants() {}
}
