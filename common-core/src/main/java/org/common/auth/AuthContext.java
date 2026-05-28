package org.common.auth;

/**
 * 当前请求的登录用户上下文。
 * <p>下游服务从 HTTP Header 取（X-User-Id / X-User-Channel / X-User-Name），
 * 由 Controller 或拦截器写入 ThreadLocal，业务代码通过 {@link #current()} 读取。</p>
 */
public final class AuthContext {

    public static final String HEADER_USER_ID      = "X-User-Id";
    public static final String HEADER_USER_CHANNEL = "X-User-Channel";
    public static final String HEADER_USER_NAME    = "X-User-Name";

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    private final Long userId;
    private final String channel;
    private final String name;

    public AuthContext(Long userId, String channel, String name) {
        this.userId = userId;
        this.channel = channel;
        this.name = name;
    }

    public Long getUserId()  { return userId;  }
    public String getChannel() { return channel; }
    public String getName()    { return name;    }

    public static void set(AuthContext ctx) { HOLDER.set(ctx); }
    public static AuthContext current()     { return HOLDER.get(); }
    public static void clear()              { HOLDER.remove(); }
}
