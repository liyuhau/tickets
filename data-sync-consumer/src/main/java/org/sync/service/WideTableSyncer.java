package org.sync.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.sync.model.BookingWideDoc;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ES 大宽表同步服务：将 booking / user / product 三张表的 CDC 事件聚合成 booking_wide 索引文档。
 */
@Slf4j
@Service
public class WideTableSyncer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final BookingWideEsService bookingWideEsService;

    private static final String DIM_USER_PREFIX    = "dim:user:";
    private static final String DIM_PRODUCT_PREFIX = "dim:product:";
    private static final long DIM_TTL_SECONDS = 86400;

    public WideTableSyncer(StringRedisTemplate redis, BookingWideEsService bookingWideEsService) {
        this.redis = redis;
        this.bookingWideEsService = bookingWideEsService;
    }

    // ==================== 对外入口 ====================

    /**
     * booking 变更 → 组装大宽表写入 ES
     */
    public void onBookingChange(String op, JsonNode after, JsonNode before) {
        if ("d".equals(op)) {
            deleteWideDoc(before);
            return;
        }
        if (after == null || after.isNull()) return;

        Map<String, Object> afterMap = MAPPER.convertValue(after, new TypeReference<>() {});
        BookingWideDoc doc = BookingWideDoc.fromCdcAfter(afterMap);

        // 从 Redis 读维度数据，补充 user + product 字段
        doc.enrichUser(getDimFromRedis(DIM_USER_PREFIX, String.valueOf(doc.getUserId())));
        doc.enrichProduct(getDimFromRedis(DIM_PRODUCT_PREFIX, doc.getProductId()));

        upsertWideDoc(doc);
    }

    /**
     * user 变更 → 更新维度缓存
     */
    public void onUserChange(String op, JsonNode after, JsonNode before) {
        if ("d".equals(op)) {
            if (before != null) redis.delete(DIM_USER_PREFIX + before.path("id").asText());
            return;
        }
        if (after == null || after.isNull()) return;

        String userId = after.path("id").asText();
        Map<String, Object> userMap = MAPPER.convertValue(after, new TypeReference<>() {});

        // 1. 更新维度缓存
        cacheDim(DIM_USER_PREFIX, userId, userMap);
    }

    /**
     * product 变更 → 更新维度缓存
     */
    public void onProductChange(String op, JsonNode after, JsonNode before) {
        if ("d".equals(op)) {
            if (before != null) redis.delete(DIM_PRODUCT_PREFIX + before.path("product_id").asText());
            return;
        }
        if (after == null || after.isNull()) return;

        String productId = after.path("product_id").asText();
        Map<String, Object> productMap = MAPPER.convertValue(after, new TypeReference<>() {});

        // 1. 更新维度缓存
        cacheDim(DIM_PRODUCT_PREFIX, productId, productMap);
    }

    // ==================== 内部方法 ====================

    /** 写入/更新 ES 大宽表文档 */
    private void upsertWideDoc(BookingWideDoc doc) {
        bookingWideEsService.upsert(doc);
    }

    /** 删除 ES 大宽表文档 */
    private void deleteWideDoc(JsonNode before) {
        if (before == null || before.isNull()) return;
        bookingWideEsService.deleteById(before.path("id").asText());
    }

    /** 维度数据写入 Redis 缓存 */
    private void cacheDim(String prefix, String id, Map<String, Object> data) {
        try {
            String json = MAPPER.writeValueAsString(data);
            redis.opsForValue().set(prefix + id, json, DIM_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[维度缓存] 写入失败 {}{}: {}", prefix, id, e.getMessage());
        }
    }

    /** 从 Redis 读维度缓存 */
    private Map<String, Object> getDimFromRedis(String prefix, String id) {
        try {
            String json = redis.opsForValue().get(prefix + id);
            if (json != null) {
                return MAPPER.readValue(json, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("[维度缓存] 读取失败 {}{}: {}", prefix, id, e.getMessage());
        }
        return null;
    }
}
