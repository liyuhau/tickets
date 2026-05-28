package org.user.simple.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth2 Authorization Server 配置（基于 spring-authorization-server 1.2.x）。
 *
 * <p>对外暴露标准端点：</p>
 * <ul>
 *   <li>{@code POST /oauth2/token}  —— 同业渠道用 client_credentials 拿 access_token</li>
 *   <li>{@code GET  /oauth2/jwks}   —— 网关侧 Resource Server 拉取公钥验签</li>
 *   <li>{@code GET  /.well-known/oauth-authorization-server} —— 元数据</li>
 * </ul>
 *
 * <p>演示注册了 3 个同业渠道客户端：CTRIP / FLIGGY / EXPEDIA。
 * 通过 {@link OAuth2TokenCustomizer} 把 channel/name/userId 注入到 JWT 自定义声明，
 * 网关解析后通过 {@code X-User-*} Header 透传给下游业务服务。</p>
 *
 * <p>生产建议：</p>
 * <ol>
 *   <li>{@link InMemoryRegisteredClientRepository} 换成基于 MySQL 的实现</li>
 *   <li>RSA 私钥从外部密钥管理服务（KMS / HashiCorp Vault）加载，禁用启动时随机生成</li>
 *   <li>同时启用 authorization_code + PKCE，对接前端 UI 登录</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(OAuth2ClientsProperties.class)
public class AuthorizationServerConfig {

    // ---------- 1. AS 自身的安全过滤链（最高优先级） ----------
    @Bean
    @Order(1)
    public SecurityFilterChain asSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        return http.formLogin(Customizer.withDefaults()).build();
    }

    // ---------- 2. 其他端点（actuator / jwks 等）放行 ----------
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .csrf(c -> c.disable());
        return http.build();
    }

    // ---------- 3. 注册同业渠道 OAuth2 客户端 ----------
    //   ▸ 仓储：JdbcRegisteredClientRepository（持久化到 oauth2_registered_client 表）
    //   ▸ 数据来源：oauth2.clients yaml/Nacos 配置；启动时 upsert 到 DB（按 clientId 幂等）
    //   ▸ 这样既保留"配置即代码"风格，又能：① AS 重启不丢；② 运维可临时改 DB 热���效
    @Bean
    public RegisteredClientRepository registeredClientRepository(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
            PasswordEncoder encoder,
            OAuth2ClientsProperties props) {

        org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository repo =
                new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(jdbcTemplate);

        for (OAuth2ClientsProperties.ClientDef c : props.getClients()) {
            RegisteredClient existing = repo.findByClientId(c.getClientId());
            // 同 clientId 已存在 → 复用其 internal id，保证 upsert 而不是冲突
            String internalId = existing != null ? existing.getId() : UUID.randomUUID().toString();

            RegisteredClient.Builder b = RegisteredClient.withId(internalId)
                    .clientId(c.getClientId())
                    .clientSecret(encoder.encode(c.getClientSecret()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .clientSettings(ClientSettings.builder().requireProofKey(false).build())
                    .tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(c.getAccessTokenTtl())
                            .refreshTokenTimeToLive(c.getRefreshTokenTtl())
                            .reuseRefreshTokens(c.isReuseRefreshTokens())
                            .build());
            for (String gt : c.getGrantTypes()) {
                b.authorizationGrantType(new AuthorizationGrantType(gt));
            }
            c.getRedirectUris().forEach(b::redirectUri);
            c.getScopes().forEach(b::scope);

            // save 同时具备 INSERT 和 UPDATE 语义（按 internal id 匹配）
            repo.save(b.build());
        }
        return repo;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ---------- 4. JWT 自定义声明：channel / userId / name（也从配置取） ----------
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(OAuth2ClientsProperties props) {
        Map<String, OAuth2ClientsProperties.ClientDef> byId = new HashMap<>();
        props.getClients().forEach(c -> byId.put(c.getClientId(), c));
        return ctx -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(ctx.getTokenType())) return;
            OAuth2ClientsProperties.ClientDef c = byId.get(ctx.getRegisteredClient().getClientId());
            if (c == null) return;
            ctx.getClaims().claims(claims -> {
                claims.put("channel", c.getChannel());
                claims.put("userId",  c.getUserId());
                claims.put("name",    c.getName());
            });
            if (c.getUserId() != null) {
                ctx.getClaims().subject(String.valueOf(c.getUserId()));
            }
        };
    }

    // ---------- 5. RSA 签名密钥 ----------
    //   迁移到 {@link JwkSourceConfig}：支持 keystore / pem / random 三种密钥来源，
    //   生产请用 keystore + K8s Secret/Vault 挂载，避免重启换 key 致存量 JWT 全部失效。

    @Value("${oauth2.issuer:http://127.0.0.1:8081}")
    private String issuer;

    // ---------- 6. AS 元数据：issuer ----------
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                // issuer 必须与 Gateway 的 spring.security.oauth2.resourceserver.jwt.issuer-uri 完全一致
                .issuer(issuer)
                .build();
    }

    @SuppressWarnings("unused")
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(2);
}
