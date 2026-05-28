package org.inventory.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import org.inventory.api.ProductType;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 旅游产品（机票/酒店）持久化对象，对应 MySQL 表 {@code product}。
 * <p>使用 MyBatis-Plus 注解：</p>
 * <ul>
 *   <li>{@code @TableName} 关联表名</li>
 *   <li>{@code @TableId} 标记主键</li>
 *   <li>{@code @Version} 乐观锁字段（需开启 {@code OptimisticLockerInnerInterceptor}）</li>
 *   <li>{@code @TableField(fill = ...)} 配合 MetaObjectHandler 自动写入时间戳</li>
 * </ul>
 */
@TableName("product")
public class ProductPO {

    @TableId(value = "product_id", type = IdType.INPUT)
    private String productId;

    private ProductType type;

    private String name;

    private LocalDate travelDate;

    private Long priceCents;

    private Integer stock;

    /** 乐观锁版本号 */
    @Version
    private Long version;

    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

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
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }
    public Instant getUpdateTime() { return updateTime; }
    public void setUpdateTime(Instant updateTime) { this.updateTime = updateTime; }
}
