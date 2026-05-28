package org.order.controller;

import org.common.BusinessException;
import org.common.R;
import org.common.ResultCode;
import org.common.auth.AuthContext;
import org.inventory.api.StockDTO;
import org.order.client.InventoryClient;
import org.order.config.OrderDynamicProperties;
import org.order.entity.Order;
import org.order.mapper.BookingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 旅游预订中心 REST 入口（MyBatis 持久化版）。
 *
 * <pre>
 *   POST /bookings
 *   GET  /bookings
 *   GET  /bookings/{id}
 * </pre>
 *
 * 创建流程：
 * <ol>
 *   <li>幂等校验：channel + channelOrderNo 已存在直接返回原单</li>
 *   <li>Dubbo 调产品中心 deduct（远端事务，悲观锁防超卖）</li>
 *   <li>本地事务写入 booking 表 → binlog → Debezium → Kafka → ES/Redis/数仓</li>
 *   <li>若步骤 3 失败需补偿 restock（生产代码应做 TCC / 可靠消息）</li>
 * </ol>
 */
@RestController
@RequestMapping("/bookings")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final InventoryClient inventoryClient;
    private final OrderDynamicProperties props;
    private final BookingMapper bookingMapper;

    public OrderController(InventoryClient inventoryClient,
                           OrderDynamicProperties props,
                           BookingMapper bookingMapper) {
        this.inventoryClient = inventoryClient;
        this.props = props;
        this.bookingMapper = bookingMapper;
    }

    @PostMapping
    @Transactional
    public R<Order> create(@RequestBody CreateBookingRequest req) {
        // 0. 强制登录：身份由网关 JWT 透传到 Header，再被 AuthContextFilter 还原
        AuthContext me = AuthContext.current();
        if (me == null || me.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }

        if (req == null || req.productId == null || req.quantity == null
                || req.channelOrderNo == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "channelOrderNo/productId/quantity 必填");
        }
        if (req.quantity <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "quantity 必须大于 0");
        }
        if (req.quantity > props.getMaxQuantityPerRequest()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "单订单份数超过上限: " + props.getMaxQuantityPerRequest());
        }
        // 以登录用户为准，禁止客户端伪造 userId / channel
        Long userId  = me.getUserId();
        String channel = (me.getChannel() != null && !me.getChannel().isBlank())
                ? me.getChannel() : req.channel;
        if (channel == null || channel.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "channel 必填");
        }

        // 幂等校验（MyBatis-Plus LambdaQueryWrapper）
        Order existing = bookingMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                        .eq(Order::getChannel, channel)
                        .eq(Order::getChannelOrderNo, req.channelOrderNo)
                        .last("LIMIT 1"));
        if (existing != null) {
            log.info("[Booking] idempotent hit -> {}", existing);
            return R.ok(existing);
        }

        // Dubbo 调产品中心
        StockDTO stock = inventoryClient.deduct(req.productId, req.quantity);

        // 落库
        Order o = new Order();
        o.setChannel(channel);
        o.setChannelOrderNo(req.channelOrderNo);
        o.setUserId(userId);
        o.setProductId(stock.getProductId());
        o.setProductType(stock.getType().name());
        o.setQuantity(req.quantity);
        o.setTravelDate(stock.getTravelDate());
        o.setPassengerName(req.passengerName);
        o.setPassengerIdNo(req.passengerIdNo);
        o.setUnitPriceCents(stock.getPriceCents());
        o.setTotalPriceCents(stock.getPriceCents() * req.quantity);
        o.setCurrency(props.getDefaultCurrency());
        o.setStatus(props.getDefaultStatus());
        bookingMapper.insert(o);   // useGeneratedKeys 回填 id

        log.info("[Booking] created -> {}, product remaining={}", o, stock.getStock());
        return R.ok(o);
    }

    @GetMapping("/{id}")
    public R<Order> get(@PathVariable Long id) {
        Order o = bookingMapper.selectById(id);
        if (o == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND, "预订单不存在: " + id);
        }
        return R.ok(o);
    }

    @GetMapping
    public R<List<Order>> list() {
        return R.ok(bookingMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                        .orderByDesc(Order::getId)
                        .last("LIMIT 200")));
    }

    /** 创建预订单请求体 */
    public static class CreateBookingRequest {
        public String channel;
        public String channelOrderNo;
        public Long   userId;
        public String productId;
        public Integer quantity;
        public String passengerName;
        public String passengerIdNo;
    }
}
