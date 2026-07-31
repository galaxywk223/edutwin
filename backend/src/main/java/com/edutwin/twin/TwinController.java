package com.edutwin.twin;

import com.edutwin.api.StudentTwinApi;
import com.edutwin.api.model.TwinHistory;
import com.edutwin.api.model.TwinState;
import com.edutwin.api.model.StudentAnalytics;
import com.edutwin.analytics.StudentAnalyticsService;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
public class TwinController implements StudentTwinApi {

    private final TwinReadService service;
    private final StudentAnalyticsService analytics;

    public TwinController(TwinReadService service, StudentAnalyticsService analytics) {
        this.service = service;
        this.analytics = analytics;
    }

    @Override
    @PreAuthorize("@scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<TwinState> getCurrentTwin(UUID courseId, UUID studentId) {
        return ResponseEntity.ok(service.current(courseId, studentId));
    }

    @Override
    @PreAuthorize("@scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<TwinHistory> getTwinHistory(
            UUID courseId, UUID studentId, Long beforeVersion, Integer limit) {
        return ResponseEntity.ok(service.history(courseId, studentId, beforeVersion, limit));
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentAnalytics> getStudentAnalytics(UUID courseId, String period) {
        EduTwinPrincipal principal = (EduTwinPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return ResponseEntity.ok(analytics.get(courseId, principal.userId(), period));
    }
}
