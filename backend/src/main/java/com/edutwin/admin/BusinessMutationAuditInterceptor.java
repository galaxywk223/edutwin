package com.edutwin.admin;

import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
@ConditionalOnProperty(prefix = "edutwin.audit", name = "http-enabled", havingValue = "true")
public class BusinessMutationAuditInterceptor implements HandlerInterceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessMutationAuditInterceptor.class);
    public static final String REQUEST_AUDITED_ATTRIBUTE =
            BusinessMutationAuditInterceptor.class.getName() + ".requestAudited";
    public static final String ERROR_CODE_ATTRIBUTE =
            BusinessMutationAuditInterceptor.class.getName() + ".errorCode";
    public static final String ERROR_REASON_ATTRIBUTE =
            BusinessMutationAuditInterceptor.class.getName() + ".errorReason";

    private static final String CONTEXT_ATTRIBUTE =
            BusinessMutationAuditInterceptor.class.getName() + ".context";

    private final BusinessAuditService audit;
    private final Executor executor;

    public BusinessMutationAuditInterceptor(
            BusinessAuditService audit,
            @Qualifier("businessAuditExecutor") Executor executor) {
        this.audit = audit;
        this.executor = executor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isMutation(request.getMethod()) || !(handler instanceof HandlerMethod method)) return true;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof EduTwinPrincipal principal) {
            request.setAttribute(CONTEXT_ATTRIBUTE, new AuditContext(
                    principal,
                    upperSnake(method.getMethod().getName()),
                    upperSnake(method.getBeanType().getSimpleName().replaceFirst("Controller$", "")),
                    method.getBeanType().getSimpleName(),
                    method.getMethod().getName()));
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        if (!(request.getAttribute(CONTEXT_ATTRIBUTE) instanceof AuditContext context)
                || Boolean.TRUE.equals(request.getAttribute(REQUEST_AUDITED_ATTRIBUTE))) return;

        boolean succeeded = response.getStatus() < 400 && exception == null;
        String errorCode = stringAttribute(request, ERROR_CODE_ATTRIBUTE);
        String reason = stringAttribute(request, ERROR_REASON_ATTRIBUTE);
        if (!succeeded && reason == null && exception != null) reason = exception.getMessage();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("httpMethod", request.getMethod());
        metadata.put("requestPath", request.getRequestURI());
        metadata.put("handler", context.controller() + "." + context.method());
        metadata.put("httpStatus", response.getStatus());

        String targetId = targetId(request, context);
        String correlationId = CorrelationIdFilter.from(request);
        String outcome = succeeded ? "SUCCEEDED" : "FAILED";
        String capturedReason = reason;
        Map<String, Object> capturedMetadata = Map.copyOf(metadata);
        try {
            executor.execute(() -> {
                try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
                    audit.record(
                            context.principal(), context.action(), context.targetType(), targetId,
                            outcome, capturedReason, errorCode, null, null, capturedMetadata);
                } catch (RuntimeException auditFailure) {
                    LOGGER.error("Business mutation audit failed for handler={}.{}",
                            context.controller(), context.method(), auditFailure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            LOGGER.error("Business mutation audit queue rejected handler={}.{}",
                    context.controller(), context.method(), rejected);
        }
    }

    private static String targetId(HttpServletRequest request, AuditContext context) {
        Object value = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (value instanceof Map<?, ?> variables && !variables.isEmpty()) {
            Map<String, String> ordered = new TreeMap<>();
            variables.forEach((key, item) -> ordered.put(String.valueOf(key), String.valueOf(item)));
            String joined = String.join(";", ordered.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
            return joined.length() <= 160 ? joined : joined.substring(0, 160);
        }
        return context.principal().userId().toString();
    }

    private static boolean isMutation(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }

    private static String stringAttribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String upperSnake(String value) {
        return value.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }

    private record AuditContext(
            EduTwinPrincipal principal,
            String action,
            String targetType,
            String controller,
            String method) {}
}
