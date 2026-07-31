package com.edutwin.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class BusinessMutationAuditInterceptorTest {
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsFailedMutationWithoutReadingTheRequestBody() throws Exception {
        BusinessAuditService audit = mock(BusinessAuditService.class);
        BusinessMutationAuditInterceptor interceptor = new BusinessMutationAuditInterceptor(audit, Runnable::run);
        EduTwinPrincipal principal = principal();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, "", principal.getAuthorities()));

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/risk/cases/" + CASE_ID);
        request.setContent("{\"internalNote\":\"must not be audited\"}".getBytes());
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("caseId", CASE_ID));
        request.setAttribute(BusinessMutationAuditInterceptor.ERROR_CODE_ATTRIBUTE, "RISK_CASE_SCOPE_DENIED");
        request.setAttribute(BusinessMutationAuditInterceptor.ERROR_REASON_ATTRIBUTE, "Access denied");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(403);
        HandlerMethod handler = new HandlerMethod(new RiskInterventionController(), "updateCase", UUID.class);

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        verify(audit).record(
                eq(principal), eq("UPDATE_CASE"), eq("RISK_INTERVENTION"), eq("caseId=" + CASE_ID),
                eq("FAILED"), eq("Access denied"), eq("RISK_CASE_SCOPE_DENIED"),
                isNull(), isNull(), any(Map.class));
    }

    @Test
    void skipsGenericRecordWhenTheBusinessServiceAlreadyAuditedTheRequest() throws Exception {
        BusinessAuditService audit = mock(BusinessAuditService.class);
        BusinessMutationAuditInterceptor interceptor = new BusinessMutationAuditInterceptor(audit, Runnable::run);
        EduTwinPrincipal principal = principal();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, "", principal.getAuthorities()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/risk/cases");
        request.setAttribute(BusinessMutationAuditInterceptor.REQUEST_AUDITED_ATTRIBUTE, true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);
        HandlerMethod handler = new HandlerMethod(new RiskInterventionController(), "createCase");

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, null);

        verifyNoInteractions(audit);
    }

    private static EduTwinPrincipal principal() {
        return new EduTwinPrincipal(
                ACTOR_ID, "teacher", "Teacher", "", UserRole.TEACHER,
                Set.of(UserRole.TEACHER), Set.of(), true, false, 1);
    }

    private static final class RiskInterventionController {
        @SuppressWarnings("unused")
        public void updateCase(UUID caseId) {}

        @SuppressWarnings("unused")
        public void createCase() {}
    }
}
