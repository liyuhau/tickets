package org.gateway.sentinel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关 Sentinel 规则外置化（yaml/Nacos 注入）。
 *
 * <pre>
 * sentinel:
 *   gateway:
 *     channel-header: X-User-Channel
 *     block-code: 1429
 *     block-message: '请求过于频繁，请稍后再试'
 *     block-status: 429
 *     apis:
 *       booking-api:   [/api/bookings/**]
 *       inventory-api: [/api/inventory/**]
 *     api-qps:
 *       booking-api:   50
 *       inventory-api: 200
 *     channel-qps:
 *       booking-api:   20
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "sentinel.gateway")
public class SentinelGatewayRuleProperties {

    private String channelHeader = "X-User-Channel";
    private int blockCode = 1429;
    private String blockMessage = "请求过于频繁，请稍后再试";
    private int blockStatus = 429;

    private Map<String, List<String>> apis = new LinkedHashMap<>();
    private Map<String, Integer> apiQps = new LinkedHashMap<>();
    private Map<String, Integer> channelQps = new LinkedHashMap<>();

    /** 未配置 apis 时给出最小可用默认，避免启动报错 */
    public Map<String, List<String>> safeApis() {
        if (apis == null || apis.isEmpty()) {
            Map<String, List<String>> def = new LinkedHashMap<>();
            def.put("booking-api",   List.of("/api/bookings/**"));
            def.put("inventory-api", List.of("/api/inventory/**"));
            return def;
        }
        return apis;
    }

    public String getChannelHeader() { return channelHeader; }
    public void setChannelHeader(String channelHeader) { this.channelHeader = channelHeader; }
    public int getBlockCode() { return blockCode; }
    public void setBlockCode(int blockCode) { this.blockCode = blockCode; }
    public String getBlockMessage() { return blockMessage; }
    public void setBlockMessage(String blockMessage) { this.blockMessage = blockMessage; }
    public int getBlockStatus() { return blockStatus; }
    public void setBlockStatus(int blockStatus) { this.blockStatus = blockStatus; }
    public Map<String, List<String>> getApis() { return apis; }
    public void setApis(Map<String, List<String>> apis) { this.apis = apis; }
    public Map<String, Integer> getApiQps() { return apiQps; }
    public void setApiQps(Map<String, Integer> apiQps) { this.apiQps = apiQps; }
    public Map<String, Integer> getChannelQps() { return channelQps; }
    public void setChannelQps(Map<String, Integer> channelQps) { this.channelQps = channelQps; }
}
