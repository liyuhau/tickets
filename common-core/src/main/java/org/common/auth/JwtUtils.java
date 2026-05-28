package org.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类（HS256）。
 * <p>颁发方：user-service 登录接口；校验方：gateway-service 全局过滤器。
 * <p>载荷字段：</p>
 * <ul>
 *   <li>sub      —— userId（同业账号 ID）</li>
 *   <li>channel  —— 所属渠道（CTRIP/FLIGGY/...）</li>
 *   <li>name     —— 账号名（公司名）</li>
 * </ul>
 */
public final class JwtUtils {

    private JwtUtils() {}

    public static SecretKey buildKey(String secret) {
        // 密钥长度 >= 32 字节（HS256 要求 256bit）
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("jwt secret 长度必须 >= 32 字节");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /** 颁发 token */
    public static String issue(String secret, long userId, String channel, String name,
                               long ttlSeconds) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("channel", channel)
                .claim("name", name)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000))
                .signWith(buildKey(secret), Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并校验 token；过期/签名错都会抛 JwtException */
    public static Claims parse(String secret, String token) {
        return Jwts.parser()
                .verifyWith(buildKey(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
