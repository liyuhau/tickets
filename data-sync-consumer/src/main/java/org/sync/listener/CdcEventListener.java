package org.sync.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.common.http.HttpClientHelper;

/**
 * 真实消费者：订阅 Debezium 写到 Kafka 的 CDC 主题，
 * 同步到 Elasticsearch / Redis / 数仓（这里数仓打日志代替）。
 * <p>所有外部地址 / 索引前缀 / Key 模板均从配置注入，禁止硬编码。</p>
 */
@Component
public class CdcEventListener {

    private static final Logger log = LoggerFactory.getLogger(CdcEventListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final HttpClientHelper httpClient;

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String esBaseUrl;

    @Value("${cdc.es.index-prefix:}")
    private String esIndexPrefix;

    /** Redis key 模板：%s = table, %s = id */
    @Value("${cdc.redis.key-template:%s:%s}")
    private String redisKeyTemplate;

    @Value("${cdc.dw.table-prefix:ods_}")
    private String dwTablePrefix;

    public CdcEventListener(StringRedisTemplate redis, HttpClientHelper httpClient) {
        this.redis = redis;
        this.httpClient = httpClient;
    }

    @KafkaListener(topics = "#{'${cdc.topics}'.split(',')}")
    public void onMessage(String message) {
        try {
            JsonNode root = MAPPER.readTree(message);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            String op = payload.path("op").asText("?");
            String table = payload.path("source").path("table").asText("?");
            JsonNode after = payload.get("after");
            JsonNode before = payload.get("before");

            log.info("[CDC] table={} op={} before={} after={}", table, op, before, after);
            switch (op) {
                case "c", "u", "r" -> { syncToEs(table, after); evictRedis(table, after); appendDw(table, op, before, after); }
                case "d" -> { deleteFromEs(table, before); evictRedis(table, before); appendDw(table, op, before, after); }
                default -> log.warn("[CDC] unknown op: {}", op);
            }
        } catch (Exception e) {
            log.error("[CDC] handle failed: {}", e.getMessage(), e);
        }
    }

    private void syncToEs(String table, JsonNode after) {
        if (after == null || after.isNull()) return;
        String id = idOf(after);
        String url = esBaseUrl + "/" + esIndexPrefix + table + "/_doc/" + id;
        try { httpClient.put(url, after.toString(), String.class); log.info("[ES] upsert {}/{}", table, id); }
        catch (Exception e) { log.warn("[ES] upsert failed: {}", e.getMessage()); }
    }

    private void deleteFromEs(String table, JsonNode before) {
        if (before == null || before.isNull()) return;
        String id = idOf(before);
        String url = esBaseUrl + "/" + esIndexPrefix + table + "/_doc/" + id;
        try { httpClient.delete(url, String.class); log.info("[ES] delete {}/{}", table, id); }
        catch (Exception e) { log.warn("[ES] delete failed: {}", e.getMessage()); }
    }

    private void evictRedis(String table, JsonNode row) {
        if (row == null || row.isNull()) return;
        String key = String.format(redisKeyTemplate, table, idOf(row));
        redis.delete(key);
        log.info("[Redis] evict {}", key);
    }

    private void appendDw(String table, String op, JsonNode before, JsonNode after) {
        log.info("[DW] {}{} op={} before={} after={}", dwTablePrefix, table, op, before, after);
    }

    private String idOf(JsonNode row) {
        if (row.has("id")) return row.get("id").asText();
        if (row.has("product_id")) return row.get("product_id").asText();
        return String.valueOf(row.hashCode());
    }
}
