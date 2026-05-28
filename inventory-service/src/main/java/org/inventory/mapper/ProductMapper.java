package org.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.inventory.entity.ProductPO;

/**
 * 产品 Mapper（基于 MyBatis-Plus {@link BaseMapper}）。
 * <p>常见 CRUD（selectById/insert/updateById/deleteById/selectList...）由 BaseMapper 提供，
 * 业务侧只需扩展定制化 SQL：行级悲观锁、原子库存扣减等。</p>
 */
@Mapper
public interface ProductMapper extends BaseMapper<ProductPO> {

    /** 行级悲观锁查询，写库前调用，避免并发超卖 */
    @Select("SELECT * FROM product WHERE product_id = #{productId} FOR UPDATE")
    ProductPO selectByIdForUpdate(@Param("productId") String productId);

    /**
     * 原子化库存变更（乐观锁 + 余量保护，无需先查再更新）：
     * <pre>
     *   UPDATE product
     *      SET stock = stock + #{delta}, version = version + 1
     *    WHERE product_id = #{productId}
     *      AND version    = #{version}
     *      AND stock + #{delta} &gt;= 0
     * </pre>
     * 返回 0 即表示并发冲突或库存不足。
     */
    @Update("UPDATE product SET stock = stock + #{delta}, version = version + 1 "
          + "WHERE product_id = #{productId} AND version = #{version} "
          + "AND stock + #{delta} >= 0")
    int updateStock(@Param("productId") String productId,
                    @Param("delta") int delta,
                    @Param("version") long version);
}
