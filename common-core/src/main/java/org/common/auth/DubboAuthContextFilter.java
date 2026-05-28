package org.common.auth;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.MDC;

/**
 * Dubbo 端的身份透传：
 * <ul>
 *   <li><b>Consumer 侧</b>：发起 RPC 前，把当前线程 {@link AuthContext} 写入 Dubbo Attachment。</li>
 *   <li><b>Provider 侧</b>：收到 RPC 后，从 Attachment 还原 {@link AuthContext} 到 ThreadLocal，
 *       业务方法直接 {@code AuthContext.current()} 即可。</li>
 * </ul>
 *
 * <p>这样 Order(HTTP) → Inventory(Dubbo) 的整条链路都能用同一行
 * {@code AuthContext.current().getUserId()} 拿到登录用户，无需重复读 Header。</p>
 *
 * <p>SPI 注册见 {@code META-INF/dubbo/org.apache.dubbo.rpc.Filter}。</p>
 */
@Activate(group = {CommonConstants.CONSUMER, CommonConstants.PROVIDER}, order = -10000)
public class DubboAuthContextFilter implements Filter {

    public static final String ATTACH_USER_ID      = "x-user-id";
    public static final String ATTACH_USER_CHANNEL = "x-user-channel";
    public static final String ATTACH_USER_NAME    = "x-user-name";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        boolean isConsumer = RpcContext.getServiceContext().isConsumerSide();
        if (isConsumer) {
            AuthContext ctx = AuthContext.current();
            if (ctx != null && ctx.getUserId() != null) {
                invocation.setAttachment(ATTACH_USER_ID, String.valueOf(ctx.getUserId()));
                if (ctx.getChannel() != null) invocation.setAttachment(ATTACH_USER_CHANNEL, ctx.getChannel());
                if (ctx.getName()    != null) invocation.setAttachment(ATTACH_USER_NAME,    ctx.getName());
            }
            return invoker.invoke(invocation);
        }

        // Provider 侧
        boolean own = false;
        try {
            if (AuthContext.current() == null) {
                String uid = invocation.getAttachment(ATTACH_USER_ID);
                if (uid != null && !uid.isBlank()) {
                    try {
                        AuthContext.set(new AuthContext(
                                Long.valueOf(uid),
                                invocation.getAttachment(ATTACH_USER_CHANNEL),
                                invocation.getAttachment(ATTACH_USER_NAME)));
                        MDC.put("userId", uid);
                        own = true;
                    } catch (NumberFormatException ignored) {
                        // 上游异常 attachment，忽略
                    }
                }
            }
            return invoker.invoke(invocation);
        } finally {
            if (own) {
                AuthContext.clear();
                MDC.remove("userId");
            }
        }
    }
}
