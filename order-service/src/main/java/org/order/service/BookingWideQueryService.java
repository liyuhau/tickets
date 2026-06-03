package org.order.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.common.http.HttpClientHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 订单 ES 大宽表查询服务。
 * <p>
 * 分库分表后，跨表关联查询不走 MySQL JOIN，改为查询 ES {@code booking_wide} 索引。
 * CDC 已实时把 booking + user + product 三表数据聚合到该索引中。
 * </p>
 *
 * <pre>
 *  前端请求 → OrderController → BookingWideQueryService → ES booking_wide
 *                                                          (已包含 user/product 字段)
 * </pre>
 */
@Service
public class BookingWideQueryService {

    private static final Logger log = LoggerFactory.getLogger(BookingWideQueryService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClientHelper httpClient;

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esBaseUrl;

    private static final String WIDE_INDEX = "booking_wide";

    public BookingWideQueryService(HttpClientHelper httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 按订单 ID 查询大宽表（精确匹配）
     *
     * @return 宽表文档 Map，包含 booking + user + product 所有字段；不存在返回 null
     */
    public Map<String, Object> getById(Long bookingId) {
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_doc/" + bookingId;
        try {
            String json = httpClient.get(url, String.class);
            JsonNode root = MAPPER.readTree(json);
            if (root.path("found").asBoolean(false)) {
                return MAPPER.convertValue(root.get("_source"),
                        new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("[ES宽表] 查询失败 id={}: {}", bookingId, e.getMessage());
        }
        return null;
    }

    /**
     * 查询某用户的所有订单（大宽表，带 user/product 信息）
     *
     * @param userId 用户 ID
     * @param from   分页起始位置（0-based）
     * @param size   每页条数
     * @return 宽表文档列表
     */
    public List<Map<String, Object>> listByUserId(Long userId, int from, int size) {
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_search";
        Map<String, Object> body = Map.of(
                "query", Map.of("term", Map.of("userId", userId)),
                "sort",  List.of(Map.of("createTime", Map.of("order", "desc"))),
                "from",  from,
                "size",  size
        );
        return doSearch(url, body);
    }

    /**
     * 多条件组合查询（渠道 + 状态 + 出行日期范围 + 产品类型 + 关键词等）
     * <p>典型场景：运营后台"按条件搜索订单"，跨库 JOIN 场景全走 ES。</p>
     */
    public List<Map<String, Object>> search(String channel, String status,
                                            String productType, String keyword,
                                            String travelDateFrom, String travelDateTo,
                                            int from, int size) {
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_search";

        // 构建 bool query
        List<Map<String, Object>> must = new ArrayList<>();
        if (channel != null)     must.add(Map.of("term", Map.of("channel", channel)));
        if (status != null)      must.add(Map.of("term", Map.of("status", status)));
        if (productType != null) must.add(Map.of("term", Map.of("productType", productType)));
        if (travelDateFrom != null || travelDateTo != null) {
            Map<String, Object> range = new java.util.HashMap<>();
            if (travelDateFrom != null) range.put("gte", travelDateFrom);
            if (travelDateTo != null)   range.put("lte", travelDateTo);
            must.add(Map.of("range", Map.of("travelDate", range)));
        }
        if (keyword != null && !keyword.isBlank()) {
            must.add(Map.of("multi_match", Map.of(
                    "query",  keyword,
                    "fields", List.of("passengerName", "userName", "productName", "channelOrderNo")
            )));
        }

        Map<String, Object> query = must.isEmpty()
                ? Map.of("match_all", Map.of())
                : Map.of("bool", Map.of("must", must));

        Map<String, Object> body = Map.of(
                "query", query,
                "sort",  List.of(Map.of("createTime", Map.of("order", "desc"))),
                "from",  from,
                "size",  size
        );
        return doSearch(url, body);
    }

    // ==================== 按 ID 集合批量查询（分库分表核心场景） ====================

    /**
     * 方案一：ES mget 批量精确查询（推荐）
     * <p>
     * 适用场景：前端传入一批订单 ID，查完整宽表信息。
     * 不经过 ShardingSphere 广播，直接从 ES 取，毫秒级返回。
     * </p>
     *
     * @param ids 订单 ID 集合
     * @return 宽表文档列表（顺序与入参一致，不存在的 ID 会被跳过）
     */
    public List<Map<String, Object>> mgetByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_mget";
        // 构建 mget body: { "ids": [1, 2, 3] }
        Map<String, Object> body = Map.of("ids", ids);
        try {
            String json = httpClient.post(url, body, String.class);
            JsonNode root = MAPPER.readTree(json);
            JsonNode docs = root.path("docs");
            if (docs.isMissingNode() || !docs.isArray()) return Collections.emptyList();

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode doc : docs) {
                // mget 结果中每条都有 found 字段，只取存在的
                if (doc.path("found").asBoolean(false)) {
                    result.add(MAPPER.convertValue(doc.get("_source"),
                            new TypeReference<Map<String, Object>>() {}));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[ES宽表] mget 失败, ids={}: {}", ids, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 方案二：ES terms 查询（需要排序/分页时用）
     * <p>
     * 与 mget 的区别：terms 走搜索引擎，支持排序和分页；mget 走文档存储，更快但无排序。
     * </p>
     *
     * @param ids  订单 ID 集合
     * @param from 分页起始
     * @param size 每页条数
     * @return 按创建时间降序的宽表文档列表
     */
    public List<Map<String, Object>> searchByIds(List<Long> ids, int from, int size) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String url = esBaseUrl + "/" + WIDE_INDEX + "/_search";
        Map<String, Object> body = Map.of(
                "query", Map.of("terms", Map.of("id", ids)),
                "sort",  List.of(Map.of("createTime", Map.of("order", "desc"))),
                "from",  from,
                "size",  size
        );
        return doSearch(url, body);
    }

    // ==================== 内部方法 ====================

    private List<Map<String, Object>> doSearch(String url, Map<String, Object> body) {
        try {
            String json = httpClient.post(url, body, String.class);
            JsonNode root = MAPPER.readTree(json);
            JsonNode hits = root.path("hits").path("hits");
            if (hits.isMissingNode() || !hits.isArray()) return Collections.emptyList();

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                result.add(MAPPER.convertValue(hit.get("_source"),
                        new TypeReference<Map<String, Object>>() {}));
            }
            return result;
        } catch (Exception e) {
            log.warn("[ES宽表] search 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
