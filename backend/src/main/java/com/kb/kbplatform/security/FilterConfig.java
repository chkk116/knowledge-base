package com.kb.kbplatform.security;

import com.kb.kbplatform.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Value("${kb.security.token-secret}")
    private String tokenSecret;

    @Value("${kb.security.token-ttl:86400000}")
    private long tokenTtl;

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(UserRepository userRepo) {
        FilterRegistrationBean<AuthFilter> bean =
                new FilterRegistrationBean<>(new AuthFilter(tokenSecret, tokenTtl, userRepo));
        bean.setOrder(-100);   // 尽量靠前执行
        return bean;
    }

    /** 安全响应头：优先级高于鉴权过滤器，保证 401/403 响应也带安全头 */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> bean =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        bean.setOrder(-110);
        return bean;
    }
}
