package com.edutwin.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(prefix = "edutwin.audit", name = "http-enabled", havingValue = "true")
public class BusinessAuditWebConfiguration implements WebMvcConfigurer {
    private final BusinessMutationAuditInterceptor interceptor;

    public BusinessAuditWebConfiguration(BusinessMutationAuditInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
    }
}
