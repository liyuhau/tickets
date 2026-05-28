package org.user.simple.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OAuth2 客户端注册元数据（从 yaml/Nacos 配置加载）。
 *
 * <pre>
 * oauth2:
 *   clients:
 *     - client-id: ctrip-channel
 *       client-secret: ${CTRIP_SECRET:ctrip-secret}
 *       channel: CTRIP
 *       user-id: 1001
 *       name: CTRIP-TRAVEL-AGENCY-A
 *       scopes: [booking.read, booking.write, inventory.read]
 *       grant-types: [client_credentials, refresh_token, authorization_code]
 *       redirect-uris: [http://localhost:9000/login/oauth2/code/ctrip]
 *       access-token-ttl: PT30M       # ISO-8601 Duration: 30 分钟
 *       refresh-token-ttl: P7D        # 7 天
 *       reuse-refresh-tokens: false   # 每次刷新都旋转新 refresh_token，防重放
 * </pre>
 */
@ConfigurationProperties(prefix = "oauth2")
public class OAuth2ClientsProperties {

    private List<ClientDef> clients = new ArrayList<>();

    public List<ClientDef> getClients() { return clients; }
    public void setClients(List<ClientDef> clients) { this.clients = clients; }

    public static class ClientDef {
        private String clientId;
        private String clientSecret;
        private String channel;
        private Long userId;
        private String name;
        private List<String> scopes = new ArrayList<>();

        /** 支持的 OAuth2 授权类型；缺省 client_credentials */
        private List<String> grantTypes = new ArrayList<>(List.of("client_credentials"));

        /** authorization_code 流程必填的回调地址 */
        private List<String> redirectUris = new ArrayList<>();

        /** access_token 有效期，默认 30 分钟 */
        private Duration accessTokenTtl = Duration.ofMinutes(30);

        /** refresh_token 有效期，默认 7 天 */
        private Duration refreshTokenTtl = Duration.ofDays(7);

        /** true=每次刷新继续用同一 refresh_token；false=旋转（推荐，防重放） */
        private boolean reuseRefreshTokens = false;

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
        public List<String> getGrantTypes() { return grantTypes; }
        public void setGrantTypes(List<String> grantTypes) { this.grantTypes = grantTypes; }
        public List<String> getRedirectUris() { return redirectUris; }
        public void setRedirectUris(List<String> redirectUris) { this.redirectUris = redirectUris; }
        public Duration getAccessTokenTtl() { return accessTokenTtl; }
        public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
        public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
        public void setRefreshTokenTtl(Duration refreshTokenTtl) { this.refreshTokenTtl = refreshTokenTtl; }
        public boolean isReuseRefreshTokens() { return reuseRefreshTokens; }
        public void setReuseRefreshTokens(boolean reuseRefreshTokens) { this.reuseRefreshTokens = reuseRefreshTokens; }
    }
}
