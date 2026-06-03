package org.sync.model;

import java.util.Map;

/**
 * ES 大宽表文档：将 booking + user + product 三表数据打平到一个文档中。
 * <p>跨库 JOIN 场景下，前端直接查 ES {@code booking_wide} 索引，避免分片广播。</p>
 */
public class BookingWideDoc {

    // ========== booking 字段 ==========
    private Long id;
    private String channel;
    private String channelOrderNo;
    private Long userId;
    private String productId;
    private String productType;
    private Integer quantity;
    private String travelDate;
    private String passengerName;
    private String passengerIdNo;
    private Long unitPriceCents;
    private Long totalPriceCents;
    private String currency;
    private String status;
    private String createTime;
    private String updateTime;

    // ========== user 宽表字段（冗余） ==========
    private String userName;
    private String userChannel;
    private String userEmail;
    private String userStatus;

    // ========== product 宽表字段（冗余） ==========
    private String productName;
    private String productTravelDate;
    private Long   productPriceCents;
    private Integer productStock;

    public BookingWideDoc() {}

    /** 从 CDC after JSON 节点构建 booking 基础字段 */
    public static BookingWideDoc fromCdcAfter(Map<String, Object> after) {
        BookingWideDoc doc = new BookingWideDoc();
        doc.id               = toLong(after.get("id"));
        doc.channel          = toStr(after.get("channel"));
        doc.channelOrderNo   = toStr(after.get("channel_order_no"));
        doc.userId           = toLong(after.get("user_id"));
        doc.productId        = toStr(after.get("product_id"));
        doc.productType      = toStr(after.get("product_type"));
        doc.quantity         = toInt(after.get("quantity"));
        doc.travelDate       = toStr(after.get("travel_date"));
        doc.passengerName    = toStr(after.get("passenger_name"));
        doc.passengerIdNo    = toStr(after.get("passenger_id_no"));
        doc.unitPriceCents   = toLong(after.get("unit_price_cents"));
        doc.totalPriceCents  = toLong(after.get("total_price_cents"));
        doc.currency         = toStr(after.get("currency"));
        doc.status           = toStr(after.get("status"));
        doc.createTime       = toStr(after.get("create_time"));
        doc.updateTime       = toStr(after.get("update_time"));
        return doc;
    }

    /** 补充 user 维度字段 */
    public void enrichUser(Map<String, Object> user) {
        if (user == null) return;
        this.userName    = toStr(user.get("name"));
        this.userChannel = toStr(user.get("channel"));
        this.userEmail   = toStr(user.get("email"));
        this.userStatus  = toStr(user.get("status"));
    }

    /** 补充 product 维度字段 */
    public void enrichProduct(Map<String, Object> product) {
        if (product == null) return;
        this.productName       = toStr(product.get("name"));
        this.productTravelDate = toStr(product.get("travel_date"));
        this.productPriceCents = toLong(product.get("price_cents"));
        this.productStock      = toInt(product.get("stock"));
    }

    // ========== getter ==========
    public Long getId() { return id; }
    public String getChannel() { return channel; }
    public String getChannelOrderNo() { return channelOrderNo; }
    public Long getUserId() { return userId; }
    public String getProductId() { return productId; }
    public String getProductType() { return productType; }
    public Integer getQuantity() { return quantity; }
    public String getTravelDate() { return travelDate; }
    public String getPassengerName() { return passengerName; }
    public String getPassengerIdNo() { return passengerIdNo; }
    public Long getUnitPriceCents() { return unitPriceCents; }
    public Long getTotalPriceCents() { return totalPriceCents; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getCreateTime() { return createTime; }
    public String getUpdateTime() { return updateTime; }
    public String getUserName() { return userName; }
    public String getUserChannel() { return userChannel; }
    public String getUserEmail() { return userEmail; }
    public String getUserStatus() { return userStatus; }
    public String getProductName() { return productName; }
    public String getProductTravelDate() { return productTravelDate; }
    public Long getProductPriceCents() { return productPriceCents; }
    public Integer getProductStock() { return productStock; }

    private static String toStr(Object o)  { return o == null ? null : o.toString(); }
    private static Long   toLong(Object o) { return o == null ? null : Long.valueOf(o.toString()); }
    private static Integer toInt(Object o) { return o == null ? null : Integer.valueOf(o.toString()); }
}
