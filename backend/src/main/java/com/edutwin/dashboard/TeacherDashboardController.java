package com.edutwin.dashboard;

import com.edutwin.api.TeacherDashboardApi;
import com.edutwin.api.model.TeacherDashboard;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TeacherDashboardController implements TeacherDashboardApi {

    private final TeacherDashboardService service;

    public TeacherDashboardController(TeacherDashboardService service) {
        this.service = service;
    }

    @Override
    @PreAuthorize("hasRole('TEACHER') and @scopeAuthorization.canAccessCourse(authentication, #courseId)")
    public ResponseEntity<TeacherDashboard> getTeacherDashboard(UUID courseId, String period) {
        return ResponseEntity.ok(service.get(courseId, period));
    }
}
