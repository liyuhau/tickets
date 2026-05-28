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

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String uid = req.getHeader(AuthContext.HEADER_USER_ID);
        if (uid != null && !uid.isBlank()) {
            try {
                AuthContext.set(new AuthContext(
                        Long.valueOf(uid),
                        req.getHeader(AuthContext.HEADER_USER_CHANNEL),
                        req.getHeader(AuthContext.HEADER_USER_NAME)));
                MDC.put("userId", uid);
            } catch (NumberFormatException ignored) {
                // 头被篡改，直接忽略
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            AuthContext.clear();
            MDC.remove("userId");
        }
    }
}
