package org.sync.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.common.http.HttpClientHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.sync.model.BookingWideDoc;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ES 大宽表同步服务：将 booking / user / product 三张表的 CDC 事件
 * 聚合成 {@code booking_wide} 索引的一条文档，实现"跨库 JOIN → ES 查询"。
 *
 * <pre>
 * ┌─────────┐
 * │ booking │──┐
 * └─────────┘  │     ┌──────────────────┐      ┌──────────────────┐
 *              ├────→│ WideTableSyncer  │─────→│ ES: booking_wide │
 * ┌─────────┐  │     └──────────────────┘      └──────────────────┘
 * │  user   │──┤           ▲
 * └─────────┘  │           │ 维度缓存 (Redis)
 * ┌─────────┐  │           │
 * │ product │──┘     ┌─────┴──────┐
 * └─────────┘        │   Redis    │
 *                    └────────────┘
 * </pre>
 *
 * <b>核心思路</b>：
 * <ol>
 *   <li>user / product 变更 → 更新 Redis 维度缓存 + 部分更新相关的宽表文档</li>
 *   <li>booking 变更 → 从 Redis 读 user + product 维度 → 组装大宽表 → 写入 ES</li>
 * </ol>
 */
@Slf4j
@Service
public class WideTableSyncer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClientHelper httpClient;
    private final StringRedisTemplate redis;

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esBaseUrl;

    /** 大宽表索引名 */
    private static final String WIDE_INDEX = "booking_wide";
    /** 维度缓存 Redis key 前缀 */
    private static final String DIM_USER_PREFIX    = "dim:user:";
    private static final String DIM_PRODUCT_PREFIX = "dim:product:";
    /** 维度缓存过期时间（秒） */
    private static final long DIM_TTL_SECONDS = 86400;

    public WideTableSyncer(HttpClientHelper httpClient, StringRedisTemplate redis) {
        this.httpClient = httpClient;
        this.redis = redis;
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
     * user 变更 → 更新维度缓存 + 批量刷新相关宽表文档中的 user 字段
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

        // 2. 用 ES update_by_query 批量刷新所有该 user 的宽表文档
        updateWideDocsByQuery("userId", userId, Map.of(
                "userName",    strVal(userMap, "name"),
                "userChannel", strVal(userMap, "channel"),
                "userEmail",   strVal(userMap, "email"),
                "userStatus",  strVal(userMap, "status")
        ));
    }

    /**
     * product 变更 → 更新维度缓存 + 批量刷新相关宽表文档中的 product 字段
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

        // 2. 批量刷新所有该 product 的宽表文档
        updateWideDocsByQuery("productId", productId, Map.of(
                "productName",       strVal(productMap, "name"),
                "productTravelDate", strVal(productMap, "travel_date"),
                "productPriceCents", productMap.getOrDefault("price_cents", 0),
                "productStock",      productMap.getOrDefault("stock", 0)
        ));
    }

    // ==================== 内部方法 ====================

    /** 写入/更新 ES 大宽表文档 */
    private void upsertWideDoc(BookingWideDoc doc) {
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_doc/" + doc.getId();
        try {
            httpClient.put(url, doc, String.class);
            log.info("[大宽表] upsert booking_wide/{}", doc.getId());
        } catch (Exception e) {
            log.warn("[大宽表] upsert 失败: {}", e.getMessage());
        }
    }

    /** 删除 ES 大宽表文档 */
    private void deleteWideDoc(JsonNode before) {
        if (before == null || before.isNull()) return;
        String id = before.path("id").asText();
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_doc/" + id;
        try {
            httpClient.delete(url, String.class);
            log.info("[大宽表] delete booking_wide/{}", id);
        } catch (Exception e) {
            log.warn("[大宽表] delete 失败: {}", e.getMessage());
        }
    }

    /**
     * 用 ES _update_by_query 批量更新宽表文档中的维度字段。
     * 例如 user 改名后，所有该 userId 的订单文档都要更新 userName。
     */
    private void updateWideDocsByQuery(String matchField, String matchValue, Map<String, Object> fields) {
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_update_by_query";
        // 构建 painless 脚本
        StringBuilder script = new StringBuilder();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            script.append("ctx._source.").append(e.getKey())
                  .append(" = params.").append(e.getKey()).append("; ");
        }
        Map<String, Object> body = Map.of(
                "query", Map.of("term", Map.of(matchField, matchValue)),
                "script", Map.of(
                        "source", script.toString().trim(),
                        "params", fields
                )
        );
        try {
            httpClient.post(url, body, String.class);
            log.info("[大宽表] update_by_query {}={}, 更新字段: {}", matchField, matchValue, fields.keySet());
        } catch (Exception e) {
            log.warn("[大宽表] update_by_query 失败: {}", e.getMessage());
        }
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

    private static String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }
}
