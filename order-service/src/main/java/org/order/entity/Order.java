package org.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 旅游预订单（同业 B2B 渠道下单产生），对应 MySQL 表 {@code booking}。
 * <p>使用 MyBatis-Plus 注解；时间字段由 {@code MetaObjectHandler} 自动填充。</p>
 */
@TableName("booking")
public class Order {

    /** 分库分表后使用雪花算法生成分布式唯一 ID（由 ShardingSphere keyGenerateStrategy 驱动） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String channel;
    private String channelOrderNo;
    private Long userId;
    private String productId;
    private String productType;
    private Integer quantity;
    private LocalDate travelDate;
    private String passengerName;
    private String passengerIdNo;
    private Long unitPriceCents;
    private Long totalPriceCents;
    private String currency;
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

    public Order() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public LocalDate getTravelDate() { return travelDate; }
    public void setTravelDate(LocalDate travelDate) { this.travelDate = travelDate; }
    public String getPassengerName() { return passengerName; }
    public void setPassengerName(String passengerName) { this.passengerName = passengerName; }
    public String getPassengerIdNo() { return passengerIdNo; }
    public void setPassengerIdNo(String passengerIdNo) { this.passengerIdNo = passengerIdNo; }
    public Long getUnitPriceCents() { return unitPriceCents; }
    public void setUnitPriceCents(Long unitPriceCents) { this.unitPriceCents = unitPriceCents; }
    public Long getTotalPriceCents() { return totalPriceCents; }
    public void setTotalPriceCents(Long totalPriceCents) { this.totalPriceCents = totalPriceCents; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }
    public Instant getUpdateTime() { return updateTime; }
    public void setUpdateTime(Instant updateTime) { this.updateTime = updateTime; }

    @Override
    public String toString() {
        return "Order{id=" + id + ", channel=" + channel + ", productId=" + productId +
                ", type=" + productType + ", qty=" + quantity + ", travelDate=" + travelDate +
                ", total=" + totalPriceCents + currency + ", status=" + status + "}";
    }
}
