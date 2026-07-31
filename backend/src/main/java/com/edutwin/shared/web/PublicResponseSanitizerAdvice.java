package com.edutwin.shared.web;

import com.edutwin.identity.EduTwinPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ControllerAdvice
public class PublicResponseSanitizerAdvice implements ResponseBodyAdvice<Object> {
    private final PublicPayloadSanitizer sanitizer;
    private final HttpServletRequest servletRequest;

    public PublicResponseSanitizerAdvice(
            PublicPayloadSanitizer sanitizer, HttpServletRequest servletRequest) {
        this.sanitizer = sanitizer;
        this.servletRequest = servletRequest;
    }

    @Override
    public boolean supports(
            MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !SseEmitter.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null || !servletRequest.getRequestURI().startsWith("/api/v1/")
                || servletRequest.getRequestURI().startsWith("/api/v1/admin/")
                || isAdmin()) {
            return body;
        }
        return sanitizer.sanitize(body);
    }

    private boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof EduTwinPrincipal principal
                && "ADMIN".equals(principal.role().getValue());
    }
}
