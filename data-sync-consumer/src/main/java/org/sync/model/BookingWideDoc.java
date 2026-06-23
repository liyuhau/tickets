package org.sync.model;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Map;

/**
 * ES 大宽表文档：将 booking + user + product 三表数据打平到一个文档中。
 */
@Getter
@Document(indexName = BookingWideIndexNames.INDEX_V1)
public class BookingWideDoc {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String channel;

    @Field(type = FieldType.Keyword)
    private String channelOrderNo;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Keyword)
    private String productId;

    @Field(type = FieldType.Keyword)
    private String productType;

    @Field(type = FieldType.Integer)
    private Integer quantity;

    @Field(type = FieldType.Keyword)
    private String travelDate;

    @Field(type = FieldType.Text)
    private String passengerName;

    @Field(type = FieldType.Keyword)
    private String passengerIdNo;

    @Field(type = FieldType.Long)
    private Long unitPriceCents;

    @Field(type = FieldType.Long)
    private Long totalPriceCents;

    @Field(type = FieldType.Keyword)
    private String currency;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String createTime;

    @Field(type = FieldType.Keyword)
    private String updateTime;

    @Field(type = FieldType.Text)
    private String userName;

    @Field(type = FieldType.Keyword)
    private String userChannel;

    @Field(type = FieldType.Keyword)
    private String userEmail;

    @Field(type = FieldType.Keyword)
    private String userStatus;

    @Field(type = FieldType.Text)
    private String productName;

    @Field(type = FieldType.Keyword)
    private String productTravelDate;

    @Field(type = FieldType.Long)
    private Long productPriceCents;

    @Field(type = FieldType.Integer)
    private Integer productStock;

    public BookingWideDoc() {}

    public static BookingWideDoc fromCdcAfter(Map<String, Object> after) {
        BookingWideDoc doc = new BookingWideDoc();
        doc.id = toStr(after.get("id"));
        doc.channel = toStr(after.get("channel"));
        doc.channelOrderNo = toStr(after.get("channel_order_no"));
        doc.userId = toLong(after.get("user_id"));
        doc.productId = toStr(after.get("product_id"));
        doc.productType = toStr(after.get("product_type"));
        doc.quantity = toInt(after.get("quantity"));
        doc.travelDate = toStr(after.get("travel_date"));
        doc.passengerName = toStr(after.get("passenger_name"));
        doc.passengerIdNo = toStr(after.get("passenger_id_no"));
        doc.unitPriceCents = toLong(after.get("unit_price_cents"));
        doc.totalPriceCents = toLong(after.get("total_price_cents"));
        doc.currency = toStr(after.get("currency"));
        doc.status = toStr(after.get("status"));
        doc.createTime = toStr(after.get("create_time"));
        doc.updateTime = toStr(after.get("update_time"));
        return doc;
    }

    public void enrichUser(Map<String, Object> user) {
        if (user == null) return;
        this.userName = toStr(user.get("name"));
        this.userChannel = toStr(user.get("channel"));
        this.userEmail = toStr(user.get("email"));
        this.userStatus = toStr(user.get("status"));
    }

    public void enrichProduct(Map<String, Object> product) {
        if (product == null) return;
        this.productName = toStr(product.get("name"));
        this.productTravelDate = toStr(product.get("travel_date"));
        this.productPriceCents = toLong(product.get("price_cents"));
        this.productStock = toInt(product.get("stock"));
    }

    private static String toStr(Object o) { return o == null ? null : o.toString(); }
    private static Long toLong(Object o) { return o == null ? null : Long.valueOf(o.toString()); }
    private static Integer toInt(Object o) { return o == null ? null : Integer.valueOf(o.toString()); }
}
