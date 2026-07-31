package com.edutwin.admin;

import com.edutwin.identity.EduTwinPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class BusinessAuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessAuditService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public BusinessAuditService(
            JdbcClient jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    public void record(
            EduTwinPrincipal actor,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String reason,
            String errorCode,
            Object before,
            Object after,
            Object metadata) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(
                    BusinessMutationAuditInterceptor.REQUEST_AUDITED_ATTRIBUTE, true);
        }
        String correlationId = correlationId();
        Runnable persist = () -> requiresNew.executeWithoutResult(status -> persist(
                actor, action, targetType, targetId, outcome, reason, errorCode,
                before, after, metadata, correlationId));
        if ("SUCCEEDED".equals(outcome)
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        persist.run();
                    } catch (RuntimeException exception) {
                        LOGGER.error("Committed business audit persistence failed for action={} targetType={} targetId={}",
                                action, targetType, targetId, exception);
                    }
                }
            });
            return;
        }
        persist.run();
    }

    private void persist(
            EduTwinPrincipal actor,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String reason,
            String errorCode,
            Object before,
            Object after,
            Object metadata,
            String correlationId) {
        jdbc.sql("""
                        INSERT INTO admin_audit_event(
                            id, actor_user_id, active_role, action, target_type, target_id,
                            reason, before_json, after_json, outcome, error_code,
                            metadata_json, correlation_id)
                        VALUES (:id, :actor, :activeRole, :action, :targetType, :targetId,
                            :reason, CAST(:beforeJson AS JSON), CAST(:afterJson AS JSON),
                            :outcome, :errorCode, CAST(:metadataJson AS JSON), :correlationId)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("actor", actor.userId().toString())
                .param("activeRole", actor.role().getValue())
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("reason", reason)
                .param("beforeJson", json(before))
                .param("afterJson", json(after))
                .param("outcome", outcome)
                .param("errorCode", errorCode)
                .param("metadataJson", json(metadata))
                .param("correlationId", correlationId)
                .update();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Business audit serialization failed.", exception);
        }
    }

    private static String correlationId() {
        String value = MDC.get("correlationId");
        if (value != null) {
            try {
                return UUID.fromString(value).toString();
            } catch (IllegalArgumentException ignored) {
                // Non-request callers and malformed local context receive a fresh trace identifier.
            }
        }
        return UUID.randomUUID().toString();
    }
}
