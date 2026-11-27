/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.service.ApiKeyService;
import com.kb.kbplatform.service.OrgService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {

    @Value("${kb.security.token-ttl:86400000}")
    private long tokenTtl;

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(UserRepository userRepo, TokenSecretProvider tokenSecretProvider,
                                                         ApiKeyService apiKeyService, OrgService orgService) {
        FilterRegistrationBean<AuthFilter> bean =
                new FilterRegistrationBean<>(new AuthFilter(tokenSecretProvider.get(), tokenTtl, userRepo, apiKeyService, orgService));
        bean.setOrder(-100);   // 尽量靠前执行
        return bean;
    }

    /** 分页参数全局收敛（size 上限 100、page 非负）：置于鉴权之前，覆盖全部接口 */
    @Bean
    public FilterRegistrationBean<PageParamFilter> pageParamFilter() {
        FilterRegistrationBean<PageParamFilter> bean = new FilterRegistrationBean<>(new PageParamFilter());
        bean.setOrder(-120);
        bean.addUrlPatterns("/api/*");
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
