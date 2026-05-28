package org.gateway.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 让 OAuth2 <b>token 吊销立刻生效</b>的网关全局过滤器。
 *
 * <p><b>背景：</b> 默认的 OAuth2 Resource Server 仅做本地 JWT 验签 + exp 校验，
 * 调用 {@code /oauth2/revoke} 后旧 token <b>仍能在网关侧通过</b>，直到 exp 自然过期。
 * 本过滤器在每次请求时去 AS 的 {@code /oauth2/introspect} 校验 token 真实状态，
 * 并把结果用 <b>Caffeine</b> 缓存若干秒，平衡"实时性"和"性能"。</p>
 *
 * <p><b>执行顺序：</b> 排在 Spring Security 的 JWT 验签之后（{@code order > -100}），
 * 此时已有合法 JWT；本过滤器再做"是否已吊销"的最后一道关。</p>
 *
 * <p><b>权衡：</b></p>
 * <ul>
 *   <li>关闭（{@code security.introspect.enabled=false}）—— 性能最高，吊销延迟 = access_token TTL</li>
 *   <li>开启 + 缓存 10 秒（默认）—— 吊销延迟 ≤ 10 秒，AS QPS ≈ (业务 QPS / 缓存命中率)</li>
 *   <li>缓存 0 秒（{@code security.introspect.cache-seconds=0}）—— 强一致，每个请求都打 AS</li>
 * </ul>
 */
@Component
public class JwtRevocationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtRevocationFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${security.introspect.enabled:false}")
    private boolean enabled;

    /** AS 的 introspect 端点（必须能从网关访问到，不需要经过自己） */
    @Value("${security.introspect.uri:${spring.security.oauth2.resourceserver.jwt.issuer-uri}/oauth2/introspect}")
    private String introspectUri;

    /** 调 introspect 时的 client_credentials（任意一个已注册客户端即可，权限上等同 admin） */
    @Value("${security.introspect.client-id:ctrip-channel}")
    private String introspectClientId;

    @Value("${security.introspect.client-secret:ctrip-secret}")
    private String introspectClientSecret;

    /** 缓存秒数，0 = 不缓存（强一致） */
    @Value("${security.introspect.cache-seconds:10}")
    private long cacheSeconds;

    @Value("${security.introspect.cache-max-size:50000}")
    private long cacheMaxSize;

    /** 错误码（与 ResultCode.UNAUTHORIZED 对齐） */
    @Value("${security.unauthorized.code:1401}")
    private int unauthorizedCode;

    private WebClient webClient;
    private Cache<String, Boolean> activeCache;     // key=token, value=is active

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder().build();
        this.activeCache = Caffeine.newBuilder()
                .maximumSize(cacheMaxSize)
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, cacheSeconds)))
                .build();
        if (enabled) {
            log.info("[Revocation] JwtRevocationFilter ENABLED, introspectUri={}, cacheSeconds={}",
                    introspectUri, cacheSeconds);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        String token = extractBearer(exchange.getRequest());
        if (token == null) {
            // 没有 token 的请求由 Spring Security 决定（白名单放行，其余 401）
            return chain.filter(exchange);
        }

        // 缓存 0 秒 = 每次实时打 AS
        Boolean cached = cacheSeconds > 0 ? activeCache.getIfPresent(token) : null;
        if (cached != null) {
            return cached ? chain.filter(exchange) : reject(exchange);
        }

        return introspect(token)
                .flatMap(active -> {
                    if (cacheSeconds > 0) activeCache.put(token, active);
                    return active ? chain.filter(exchange) : reject(exchange);
                })
                .onErrorResume(e -> {
                    // AS 故障时，按"宽松"策略放行（仅依赖本地 JWT 验签）；可改严格策略 reject
                    log.warn("[Revocation] introspect failed, fail-open: {}", e.getMessage());
                    return chain.filter(exchange);
                });
    }

    private Mono<Boolean> introspect(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        return webClient.post()
                .uri(introspectUri)
                .headers(h -> h.setBasicAuth(introspectClientId, introspectClientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> {
                    try {
                        JsonNode node = MAPPER.readTree(body);
                        return node.path("active").asBoolean(false);
                    } catch (Exception e) {
                        log.warn("[Revocation] parse introspect response failed: {}", body);
                        return true; // 解析失败按宽松放行
                    }
                });
    }

    private String extractBearer(ServerHttpRequest req) {
        List<String> auth = req.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (auth == null || auth.isEmpty()) return null;
        String h = auth.get(0);
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + unauthorizedCode
                + ",\"message\":\"token 已被吊��\",\"timestamp\":" + System.currentTimeMillis() + "}";
        DataBuffer buf = resp.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return resp.writeWith(Mono.just(buf));
    }

    @Override
    public int getOrder() {
        // Spring Security 的 SecurityWebFilterChain 默认 order = -100，本过滤器排在它之后
        return -50;
    }
}
