package org.common;

import org.common.auth.AuthContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import jakarta.servlet.Filter;

/**
 * common-core 自动装配入口。
 * 任何服务只要依赖 common-core，Spring Boot 启动时会自动注册：
 * <ul>
 *   <li>{@link GlobalExceptionHandler} 统一异常处理</li>
 *   <li>{@link AuthContextFilter} 从网关透传 Header 还原登录上下文（仅 Servlet Web）</li>
 * </ul>
 */
@AutoConfiguration
@Import({GlobalExceptionHandler.class, org.common.http.HttpClientAutoConfiguration.class,
        org.common.diag.ThreadDiagnosticController.class})
public class CommonAutoConfiguration {

    /** 仅在 Servlet Web 环境（Spring MVC）下注册，避免影响 gateway 的 WebFlux 体系 */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    public FilterRegistrationBean<Filter> authContextFilter(
            @Value("${spring.cloud.gateway.internal-api.secret-key:DefaultSecretKeyForDevelopmentDoNotUseInProd}") String secretKey,
            @Value("${spring.cloud.gateway.internal-api.timestamp-validity-ms:60000}") long timestampValidityMs) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new AuthContextFilter(secretKey, timestampValidityMs));
        reg.addUrlPatterns("/*");
        reg.setOrder(Integer.MIN_VALUE + 10);   // 尽早执行
        reg.setName("authContextFilter");
        return reg;
    }
}
