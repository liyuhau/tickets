package org.user.security.oauth2;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 使用 Redis 管理 JWT 吊销列表。
 */
@Service
public class RedisTokenRevocationService {

    private static final String REVOCATION_KEY_PREFIX = "jwt:revoked:";

    private final StringRedisTemplate redisTemplate;

    public RedisTokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 吊销一个令牌。
     *
     * @param jti       令牌的唯一标识 (JWT ID)
     * @param expiresIn 令牌的剩余有效时间
     */
    public void revoke(String jti, Duration expiresIn) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        // 存储 jti，并设置其过期时间为令牌的剩余有效期
        redisTemplate.opsForValue().set(REVOCATION_KEY_PREFIX + jti, "revoked", expiresIn);
    }

    /**
     * 检查令牌是否已被吊销。
     *
     * @param jti 令牌的唯一标识 (JWT ID)
     * @return 如果在吊销列表中，则返回 true
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(REVOCATION_KEY_PREFIX + jti));
    }
}
