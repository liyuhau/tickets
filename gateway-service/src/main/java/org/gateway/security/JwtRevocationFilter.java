package org.gateway.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 让 OAuth2 token 吊销立刻生效的网关全局过滤器。
 */
@Slf4j
@Component
public class JwtRevocationFilter implements GlobalFilter, Ordered {

    private static final String REVOCATION_KEY_PREFIX = "jwt:revoked:";

    @Value("${security.revocation.check.enabled:true}")
    private boolean enabled;

    @Value("${security.unauthorized.code:1401}")
    private int unauthorizedCode;

    private final ReactiveStringRedisTemplate redisTemplate;

    public JwtRevocationFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        if (enabled) {
            log.info("[Revocation] JwtRevocationFilter with Redis is ENABLED.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .flatMap(this::checkRevocation)
                .flatMap(isRevoked -> {
                    if (isRevoked) {
                        return Mono.defer(() -> writeUnauthorizedResponse(exchange.getResponse()));
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    private Mono<Boolean> checkRevocation(Jwt jwt) {
        String jti = jwt.getId();
        if (jti == null) {
            return Mono.just(false);
        }
        return redisTemplate.hasKey(REVOCATION_KEY_PREFIX + jti);
    }

    private Mono<Void> writeUnauthorizedResponse(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + unauthorizedCode + ",\"message\":\"Token has been revoked\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -60;
    }
}
