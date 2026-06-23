package org.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * 下游 Web 服务用的轻量身份过滤器：
 * 从网关透传的 Header 还原 {@link AuthContext}，请求结束清理 ThreadLocal。
 * <p>不做 JWT 校验（鉴权已由网关完成），因此性能开销近乎为零。</p>
 */
public class AuthContextFilter extends OncePerRequestFilter {

    private final String secretKey;
    private final long timestampValidityMs;

    public AuthContextFilter(String secretKey, long timestampValidityMs) {
        this.secretKey = secretKey;
        this.timestampValidityMs = timestampValidityMs;
    }

    public static final String HEADER_INTERNAL_SIGNATURE = "X-Internal-Signature";
    public static final String HEADER_INTERNAL_TIMESTAMP = "X-Internal-Timestamp";
    public static final String HEADER_INTERNAL_NONCE = "X-Internal-Nonce";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String uid = req.getHeader(AuthContext.HEADER_USER_ID);
        String signature = req.getHeader(HEADER_INTERNAL_SIGNATURE);
        String timestamp = req.getHeader(HEADER_INTERNAL_TIMESTAMP);
        String nonce = req.getHeader(HEADER_INTERNAL_NONCE);

        // 验证签名，确保请求来自网关
        if (uid != null && !uid.isBlank() && verifySignature(uid, signature, timestamp, nonce)) {
            try {
                AuthContext.set(new AuthContext(
                        Long.valueOf(uid),
                        req.getHeader(AuthContext.HEADER_USER_CHANNEL),
                        req.getHeader(AuthContext.HEADER_USER_NAME)));
                MDC.put("userId", uid);
            } catch (NumberFormatException ignored) {
                // Header被篡改，但签名通过了？可能性极小，记录一个警告。
                logger.warn("Internal auth header 'uid' was tampered but signature is valid. Investigate immediately.");
            }
        }

        try {
            chain.doFilter(req, res);
        } finally {
            AuthContext.clear();
            MDC.remove("userId");
        }
    }

    private boolean verifySignature(String uid, String signature, String timestampStr, String nonce) {
        if (signature == null || timestampStr == null || nonce == null) {
            logger.warn("Request is missing internal signature headers. Potentially a request that bypassed the gateway.");
            return false; // Or throw an exception, depending on security policy
        }

        try {
            long requestTimestamp = Long.parseLong(timestampStr);
            if (System.currentTimeMillis() - requestTimestamp > timestampValidityMs) {
                logger.warn("Internal signature timestamp is expired.");
                return false;
            }
        } catch (NumberFormatException e) {
            logger.warn("Invalid internal signature timestamp format.");
            return false;
        }

        return InternalHeaderSigner.verify(signature, uid, timestampStr, nonce, secretKey);
    }
}
