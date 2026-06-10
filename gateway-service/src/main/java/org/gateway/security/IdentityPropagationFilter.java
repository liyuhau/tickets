package org.gateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 把 OAuth2 校验通过的 JWT 关键字段透传到下游服务的请求头：
 * <ul>
 *   <li>{@code X-User-Id}      —— JWT subject（同业账号 ID）</li>
 *   <li>{@code X-User-Channel} —— 自定义声明 channel</li>
 *   <li>{@code X-User-Name}    —— 自定义声明 name</li>
 * </ul>
 * 下游 common-core 的 {@code AuthContextFilter} 读取这些 Header 还原 ThreadLocal。
 *
 * <p>排序在 Spring Security 过滤链之后、Gateway 路由之前。</p>
 */
@Slf4j
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {


    public static final String HEADER_USER_ID      = "X-User-Id";
    public static final String HEADER_USER_CHANNEL = "X-User-Channel";
    public static final String HEADER_USER_NAME    = "X-User-Name";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth instanceof JwtAuthenticationToken)
            .map(auth -> ((JwtAuthenticationToken) auth).getToken())
            .map(jwt -> mutate(exchange, jwt))
            .defaultIfEmpty(exchange)           // 白名单请求（未通过认证）原样放行
            .flatMap(chain::filter);
    }

    private ServerWebExchange mutate(ServerWebExchange exchange, Jwt jwt) {
        String userId  = jwt.getSubject();
        String channel = str(jwt.getClaim("channel"));
        String name    = str(jwt.getClaim("name"));
        if (log.isDebugEnabled()) {
            log.debug("[Gateway] propagate identity userId={} channel={}", userId, channel);
        }
        return exchange.mutate().request(
                exchange.getRequest().mutate()
                        .header(HEADER_USER_ID,      userId == null ? "" : userId)
                        .header(HEADER_USER_CHANNEL, channel)
                        .header(HEADER_USER_NAME,    name)
                        .build()).build();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    @Override
    public int getOrder() {
        // 必须在路由过滤器（NettyRoutingFilter, order=Integer.MAX_VALUE）之前
        return -50;
    }
}
