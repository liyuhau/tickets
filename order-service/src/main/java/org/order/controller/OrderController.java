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
import org.order.service.BookingWideQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
    private final BookingWideQueryService wideQueryService;

    public OrderController(InventoryClient inventoryClient,
                           OrderDynamicProperties props,
                           BookingMapper bookingMapper,
                           BookingWideQueryService wideQueryService) {
        this.inventoryClient = inventoryClient;
        this.props = props;
        this.bookingMapper = bookingMapper;
        this.wideQueryService = wideQueryService;
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

    // ==================== ES 大宽表查询（跨表 JOIN 替代方案） ====================

    /**
     * 按订单 ID 查询大宽表详情（包含 user + product 信息，不走 MySQL JOIN）
     * <p>GET /bookings/wide/123</p>
     */
    @GetMapping("/wide/{id}")
    public R<Map<String, Object>> getWide(@PathVariable Long id) {
        Map<String, Object> doc = wideQueryService.getById(id);
        if (doc == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND, "宽表订单不存在: " + id);
        }
        return R.ok(doc);
    }

    /**
     * 查询某用户的所有订单（大宽表，包含用户名/产品名等关联信息）
     * <p>GET /bookings/wide/user/1001?from=0&size=20</p>
     */
    @GetMapping("/wide/user/{userId}")
    public R<List<Map<String, Object>>> listWideByUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int from,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(wideQueryService.listByUserId(userId, from, size));
    }

    /**
     * 多条件组合搜索订单（运营后台场景：按渠道/状态/日期/关键词搜索）
     * <p>GET /bookings/wide/search?channel=CTRIP&status=CONFIRMED&keyword=张三</p>
     */
    @GetMapping("/wide/search")
    public R<List<Map<String, Object>>> searchWide(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String travelDateFrom,
            @RequestParam(required = false) String travelDateTo,
            @RequestParam(defaultValue = "0") int from,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(wideQueryService.search(channel, status, productType, keyword,
                travelDateFrom, travelDateTo, from, size));
    }

    /**
     * 按订单 ID 集合批量查询大宽表（分库分表跨片查询替代方案）
     * <p>POST /bookings/wide/mget  body: [123, 456, 789]</p>
     * <p>
     * 分片键是 user_id，按 id IN (...) 查 MySQL 会广播所有分片，性能差。
     * 改走 ES mget，毫秒级返回，不经过 ShardingSphere。
     * </p>
     */
    @PostMapping("/wide/mget")
    public R<List<Map<String, Object>>> mgetWide(@RequestBody List<Long> ids) {
        return R.ok(wideQueryService.mgetByIds(ids));
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
