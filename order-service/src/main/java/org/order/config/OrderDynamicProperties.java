package org.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 演示从 Nacos Config 动态读取属性。
 * <p>{@code @RefreshScope} 让 Bean 在 Nacos 配置变更时被重建，
 * 字段值会重新注入，无需重启服务。</p>
 *
 * 在 Nacos 控制台 dataId={@code order-service.yaml} 中配置：
 * <pre>
 * order:
 *   max-quantity-per-request: 10
 *   risk-control-enabled: true
 *   default-currency: CNY
 *   initial-status: CREATED
 * </pre>
 */
@Component
@RefreshScope
public class OrderDynamicProperties {

    @Value("${order.max-quantity-per-request:100}")
    private int maxQuantityPerRequest;

    @Value("${order.risk-control-enabled:false}")
    private boolean riskControlEnabled;

    @Value("${order.default-currency:CNY}")
    private String defaultCurrency;

    @Value("${order.default-status:CREATED}")
    private String defaultStatus;

    public int getMaxQuantityPerRequest() { return maxQuantityPerRequest; }
    public boolean isRiskControlEnabled()  { return riskControlEnabled; }
    public String getDefaultCurrency()     { return defaultCurrency; }
    public String getDefaultStatus()       { return defaultStatus; }
}
