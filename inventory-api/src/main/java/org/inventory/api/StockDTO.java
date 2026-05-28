package org.inventory.api;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 旅游产品库存信息 DTO，作为 Dubbo 序列化对象，必须实现 {@link Serializable}。
 *
 * <p>productId 使用业务可读编码，例如：</p>
 * <ul>
 *   <li>机票：{@code FLT-CA1234-20260601-Y}（航空公司+航班号+起飞日期+舱位）</li>
 *   <li>酒店：{@code HTL-SHA-MARRIOTT-DLX-20260601}（酒店+房型+入住日）</li>
 * </ul>
 */
public class StockDTO implements Serializable {

    private static final long serialVersionUID = 2L;

    private String productId;
    private ProductType type;
    /** 商品名称：航班名/酒店房型名 */
    private String name;
    /** 出行/入住日期 */
    private LocalDate travelDate;
    /** 同业结算价（分） */
    private Long priceCents;
    /** 当前可售余量 */
    private Integer stock;

    public StockDTO() {}

    public StockDTO(String productId, ProductType type, String name,
                    LocalDate travelDate, Long priceCents, Integer stock) {
        this.productId = productId;
        this.type = type;
        this.name = name;
        this.travelDate = travelDate;
        this.priceCents = priceCents;
        this.stock = stock;
    }

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public ProductType getType() { return type; }
    public void setType(ProductType type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getTravelDate() { return travelDate; }
    public void setTravelDate(LocalDate travelDate) { this.travelDate = travelDate; }

    public Long getPriceCents() { return priceCents; }
    public void setPriceCents(Long priceCents) { this.priceCents = priceCents; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    @Override
    public String toString() {
        return "StockDTO{productId='" + productId + "', type=" + type +
                ", name='" + name + "', travelDate=" + travelDate +
                ", priceCents=" + priceCents + ", stock=" + stock + "}";
    }
}
