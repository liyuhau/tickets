package org.user.security.oauth2;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 提供令牌吊销的端点。
 */
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class TokenRevocationController {

    private final OAuth2AuthorizationService authorizationService;
    private final RedisTokenRevocationService redisTokenRevocationService;

    /**
     * 吊销令牌 (Logout)
     * @param token 要吊销的 refresh token 或 access token
     */
    @PostMapping("/revoke")
    public ResponseEntity<Void> revokeToken(@RequestParam("token") String token) {
        OAuth2Authorization authorization = authorizationService.findByToken(token, null);

        if (authorization == null) {
            // 如果令牌无效或已过期，直接返回成功
            return ResponseEntity.ok().build();
        }

        // 吊销 Access Token
        OAuth2Authorization.Token<org.springframework.security.oauth2.core.OAuth2AccessToken> accessToken =
                authorization.getAccessToken();
        if (accessToken != null && accessToken.getToken() != null) {
            // 从 claims 中获取 jti (JWT ID)
            Object jtiObj = accessToken.getClaims().get("jti");
            if (jtiObj != null) {
                String jti = jtiObj.toString();
                Instant expiresAt = accessToken.getToken().getExpiresAt();
                if (expiresAt != null) {
                    Duration remainingTime = Duration.between(Instant.now(), expiresAt);
                    if (!remainingTime.isNegative()) {
                        redisTokenRevocationService.revoke(jti, remainingTime);
                    }
                }
            }
        }

        // 也可以选择吊销 Refresh Token，取决于业务需求
        // ...

        // 从数据库中移除授权记录，这会使得 refresh token 失效
        authorizationService.remove(authorization);

        return ResponseEntity.ok().build();
    }
}
