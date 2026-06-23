package org.gateway.security;

import org.common.auth.InternalHeaderSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 在将用户信息注入下游请求头之前，增加内部签名。
 * 这确保了下游服务可以验证 Header 是由网关设置的，而不是外部伪造的。
 */
@Component
public class InternalApiSignatureFilter implements GlobalFilter, Ordered {

    public static final String HEADER_INTERNAL_SIGNATURE = "X-Internal-Signature";
    public static final String HEADER_INTERNAL_TIMESTAMP = "X-Internal-Timestamp";
    public static final String HEADER_INTERNAL_NONCE = "X-Internal-Nonce";

    @Value("${spring.cloud.gateway.internal-api.secret-key:DefaultSecretKeyForDevelopmentDoNotUseInProd}")
    private String secretKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 只对已经通过认证并准备向下游传递身份的请求进行签名
        if (request.getHeaders().containsKey(IdentityPropagationFilter.HEADER_USER_ID)) {
            String userId = request.getHeaders().getFirst(IdentityPropagationFilter.HEADER_USER_ID);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();

            String signature = InternalHeaderSigner.sign(userId, timestamp, nonce, secretKey);

            ServerHttpRequest mutatedRequest = request.mutate()
                    .header(HEADER_INTERNAL_SIGNATURE, signature)
                    .header(HEADER_INTERNAL_TIMESTAMP, timestamp)
                    .header(HEADER_INTERNAL_NONCE, nonce)
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 必须在 IdentityPropagationFilter 之后执行，以确保 USER_ID 头已存在
        return IdentityPropagationFilter.ORDER + 1;
    }
}
