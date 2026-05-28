package org.user.simple.oauth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * OAuth2 授权 / 同意 的 <b>JDBC 持久化</b>实现，替换默认的 InMemory 版本。
 *
 * <p>持久化带来的能力：</p>
 * <ul>
 *   <li><b>吊销立刻持久</b>：调 {@code /oauth2/revoke} 后，记录写入 {@code oauth2_authorization}，
 *       AS 重启不丢，下次校验 / introspect 仍能识别为 invalid。</li>
 *   <li><b>refresh_token 旋转</b>记录持久（reuseRefreshTokens=false 时旧 token 被标记失效）。</li>
 *   <li><b>introspect</b> 端点能查到 token 真实状态（active / inactive）。</li>
 * </ul>
 *
 * <p>对应 schema 见 {@code infra/mysql-init/02-oauth2-schema.sql}。</p>
 *
 * <p>注意：本项目客户端仍走 {@code InMemoryRegisteredClientRepository}（从 yaml/Nacos 加载），
 * 不启用 {@code JdbcRegisteredClientRepository}——保留可观测的"配置即代码"风格。</p>
 */
@Configuration
public class OAuth2PersistenceConfig {

    /** 把授权记录（access_token / refresh_token / 吊销标记）写入 oauth2_authorization 表 */
    @Bean
    public OAuth2AuthorizationService authorizationService(
            @Autowired JdbcTemplate jdbcTemplate,
            @Autowired RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    /** 用户对客户端的"授权同意"记录（authorization_code 流程用） */
    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            @Autowired JdbcTemplate jdbcTemplate,
            @Autowired RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }
}
