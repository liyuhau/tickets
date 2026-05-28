package org.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.order.entity.Order;

/**
 * 预订单 Mapper（基于 MyBatis-Plus {@link BaseMapper}）。
 * <p>常见 CRUD 由 BaseMapper 提供；幂等查询用 LambdaQueryWrapper 在 Service 层完成。</p>
 */
@Mapper
public interface BookingMapper extends BaseMapper<Order> {
}
