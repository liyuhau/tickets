package org.common.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 企业级 HTTP 调用工具类（基于 Apache HttpClient 5 连接池 RestTemplate）。
 * <p>
 * 使用方式：直接注入 {@code HttpClientHelper} Bean 即可。
 * <pre>
 *   &#64;Autowired
 *   private HttpClientHelper httpClient;
 *
 *   R&lt;OrderDTO&gt; result = httpClient.get(url, new TypeReference&lt;R&lt;OrderDTO&gt;&gt;(){});
 * </pre>
 */
public class HttpClientHelper {

    private static final Logger log = LoggerFactory.getLogger(HttpClientHelper.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpClientHelper(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ==================== GET ====================

    /** GET 请求，返回指定类型 */
    public <T> T get(String url, Class<T> responseType) {
        return get(url, null, responseType);
    }

    /** GET 请求，支持自定义 Header */
    public <T> T get(String url, Map<String, String> headers, Class<T> responseType) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(headers));
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        return deserialize(resp.getBody(), responseType);
    }

    /** GET 请求，支持泛型（如 R&lt;List&lt;OrderDTO&gt;&gt;） */
    public <T> T get(String url, TypeReference<T> typeRef) {
        return get(url, null, typeRef);
    }

    public <T> T get(String url, Map<String, String> headers, TypeReference<T> typeRef) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(headers));
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        return deserialize(resp.getBody(), typeRef);
    }

    // ==================== POST ====================

    /** POST 请求，JSON body，返回指定类型 */
    public <T> T post(String url, Object body, Class<T> responseType) {
        return post(url, body, null, responseType);
    }

    /** POST 请求，支持自定义 Header */
    public <T> T post(String url, Object body, Map<String, String> headers, Class<T> responseType) {
        HttpHeaders httpHeaders = buildHeaders(headers);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return deserialize(resp.getBody(), responseType);
    }

    /** POST 请求，支持泛型返回 */
    public <T> T post(String url, Object body, TypeReference<T> typeRef) {
        return post(url, body, null, typeRef);
    }

    public <T> T post(String url, Object body, Map<String, String> headers, TypeReference<T> typeRef) {
        HttpHeaders httpHeaders = buildHeaders(headers);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return deserialize(resp.getBody(), typeRef);
    }

    // ==================== PUT ====================

    public <T> T put(String url, Object body, Class<T> responseType) {
        return put(url, body, null, responseType);
    }

    public <T> T put(String url, Object body, Map<String, String> headers, Class<T> responseType) {
        HttpHeaders httpHeaders = buildHeaders(headers);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, httpHeaders);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.PUT, entity, String.class);
        return deserialize(resp.getBody(), responseType);
    }

    // ==================== DELETE ====================

    public <T> T delete(String url, Class<T> responseType) {
        return delete(url, null, responseType);
    }

    public <T> T delete(String url, Map<String, String> headers, Class<T> responseType) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(headers));
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
        return deserialize(resp.getBody(), responseType);
    }

    // ==================== 原始 RestTemplate（兜底） ====================

    /** 暴露底层 RestTemplate，应对特殊场景 */
    public RestTemplate raw() {
        return restTemplate;
    }

    // ==================== 内部方法 ====================

    private HttpHeaders buildHeaders(Map<String, String> extra) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (extra != null) {
            extra.forEach(httpHeaders::set);
        }
        return httpHeaders;
    }

    private <T> T deserialize(String json, Class<T> clazz) {
        if (json == null) return null;
        if (clazz == String.class) {
            @SuppressWarnings("unchecked")
            T t = (T) json;
            return t;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("HTTP 响应反序列化失败, target={}, body={}", clazz.getSimpleName(), json, e);
            throw new RuntimeException("HTTP response deserialization failed", e);
        }
    }

    private <T> T deserialize(String json, TypeReference<T> typeRef) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.error("HTTP 响应反序列化失败, body={}", json, e);
            throw new RuntimeException("HTTP response deserialization failed", e);
        }
    }
}
