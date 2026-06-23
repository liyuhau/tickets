package org.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 内部服务间 Header 签名工具，用于验证请求是否来自可信的网关。
 * <p>
 * 使用 HMAC-SHA256 算法。
 * </p>
 */
public final class InternalHeaderSigner {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * 为透传的用户信息生成签名。
     *
     * @param userId    用户ID
     * @param timestamp 时间戳 (ms)
     * @param nonce     随机串
     * @param secretKey 签名密钥
     * @return Base64 编码的签名
     */
    public static String sign(String userId, String timestamp, String nonce, String secretKey) {
        try {
            String data = userId + ":" + timestamp + ":" + nonce;
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            // In production, you should have a dedicated logger and exception handling.
            // For this context, we'll rethrow as a runtime exception.
            throw new RuntimeException("Failed to sign internal header", e);
        }
    }

    /**
     * 校验签名是否有效。
     *
     * @param signature 待校验的签名
     * @param userId    用户ID
     * @param timestamp 时间戳 (ms)
     * @param nonce     随机串
     * @param secretKey 签名密钥
     * @return 是否有效
     */
    public static boolean verify(String signature, String userId, String timestamp, String nonce, String secretKey) {
        if (signature == null || userId == null || timestamp == null || nonce == null) {
            return false;
        }
        String expectedSignature = sign(userId, timestamp, nonce, secretKey);
        return signature.equals(expectedSignature);
    }
}
