package org.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关 Spring Security WebFlux 配置：
 * <ul>
 *   <li>白名单（token 端点、健康检查）直接放行</li>
 *   <li>其余请求要求合法 JWT（OAuth2 Resource Server 模式，通过 issuer-uri 自动拉取 JWK）</li>
 *   <li>401 / 403 统一返回 {@code R{code, message, timestamp}} JSON</li>
 * </ul>
 *
 * <p>注意：gateway 不能依赖 common-core（servlet 与 WebFlux 冲突），
 * 这里的错误码 / 错误消息均通过 {@code security.unauthorized.*}、
 * {@code security.forbidden.*} 从 yaml 注入，与 common-core 的
 * {@code ResultCode.UNAUTHORIZED(1401)} / {@code FORBIDDEN(1403)} 人工对齐。</p>
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Value("${security.white-list:/oauth2/**,/.well-known/**,/api/oauth2/**,/actuator/**}")
    private List<String> whiteList;

    @Value("${security.unauthorized.code:1401}")
    private int unauthorizedCode;

    @Value("${security.unauthorized.message:未登录或 token 无效}")
    private String unauthorizedMessage;

    @Value("${security.forbidden.code:1403}")
    private int forbiddenCode;

    @Value("${security.forbidden.message:无权访问}")
    private String forbiddenMessage;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers(whiteList.toArray(String[]::new)).permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(jsonEntryPoint())
                .accessDeniedHandler(jsonAccessDeniedHandler()));
        return http.build();
    }

    private ServerAuthenticationEntryPoint jsonEntryPoint() {
        return (exchange, ex) -> writeJson(exchange.getResponse(),
                HttpStatus.UNAUTHORIZED, unauthorizedCode, unauthorizedMessage);
    }

    private ServerAccessDeniedHandler jsonAccessDeniedHandler() {
        return (exchange, ex) -> writeJson(exchange.getResponse(),
                HttpStatus.FORBIDDEN, forbiddenCode, forbiddenMessage);
    }

    private static Mono<Void> writeJson(ServerHttpResponse resp, HttpStatus status,
                                        int code, String message) {
        resp.setStatusCode(status);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + code + ",\"message\":\"" + message
                + "\",\"timestamp\":" + System.currentTimeMillis() + "}";
        DataBuffer buf = resp.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return resp.writeWith(Mono.just(buf));
    }
}
