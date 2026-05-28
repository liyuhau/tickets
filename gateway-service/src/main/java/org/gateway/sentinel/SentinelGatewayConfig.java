package org.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sentinel 网关限流规则装配。所有阈值 / API 分组 / Header 名 / 错误码
 * 均从 {@link SentinelGatewayRuleProperties} 注入，本类不再硬编码。
 *
 * <p>说明：网关侧用 WebFlux，不能依赖 common-core（servlet 冲突），
 * 故业务码 {@code 1429} 与 {@code org.common.ResultCode.TOO_MANY_REQUESTS}
 * 保持人工对齐。</p>
 */
@Component
public class SentinelGatewayConfig {

    private final SentinelGatewayRuleProperties props;

    public SentinelGatewayConfig(SentinelGatewayRuleProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        initApiDefinitions();
        initFlowRules();
        initBlockHandler();
    }

    /** 按 yaml 配置把若干路径分组为业务 API */
    private void initApiDefinitions() {
        Set<ApiDefinition> apis = new HashSet<>();
        for (Map.Entry<String, List<String>> e : props.safeApis().entrySet()) {
            Set<ApiPredicateItem> items = new HashSet<>();
            for (String pattern : e.getValue()) {
                items.add(new ApiPathPredicateItem()
                        .setPattern(pattern)
                        .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX));
            }
            apis.add(new ApiDefinition(e.getKey()).setPredicateItems(items));
        }
        GatewayApiDefinitionManager.loadApiDefinitions(apis);
    }

    /** 全局 QPS + 按渠道 Header QPS */
    private void initFlowRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();
        props.getApiQps().forEach((apiName, qps) ->
                rules.add(new GatewayFlowRule(apiName).setCount(qps)));
        props.getChannelQps().forEach((apiName, qps) ->
                rules.add(new GatewayFlowRule(apiName)
                        .setCount(qps)
                        .setParamItem(new GatewayParamFlowItem()
                                .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_HEADER)
                                .setFieldName(props.getChannelHeader()))));
        GatewayRuleManager.loadRules(rules);
    }

    /** 限流时统一 JSON */
    private void initBlockHandler() {
        BlockRequestHandler handler = (exchange, t) -> {
            String body = "{\"code\":" + props.getBlockCode()
                    + ",\"message\":\"" + props.getBlockMessage() + "\""
                    + ",\"timestamp\":" + System.currentTimeMillis() + "}";
            return ServerResponse.status(HttpStatus.valueOf(props.getBlockStatus()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(body));
        };
        GatewayCallbackManager.setBlockHandler(handler);
    }
}
