package com.edutwin.planning;

import com.edutwin.api.DiagnosisApi;
import com.edutwin.api.LearningPlansApi;
import com.edutwin.api.model.Diagnosis;
import com.edutwin.api.model.LearningPlan;
import com.edutwin.api.model.PlanGenerationRequest;
import com.edutwin.api.model.PlanLifecycle;
import com.edutwin.api.model.SkipPlanTaskRequest;
import com.edutwin.api.model.StartPlanTaskRequest;
import com.edutwin.identity.EduTwinPrincipal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlanningController implements LearningPlansApi, DiagnosisApi {

    private final PlanningReadService service;
    private final PlanningLifecycleService lifecycleService;

    public PlanningController(
            PlanningReadService service, PlanningLifecycleService lifecycleService) {
        this.service = service;
        this.lifecycleService = lifecycleService;
    }

    @Override
    @PreAuthorize("@scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<LearningPlan> getCurrentLearningPlan(UUID courseId, UUID studentId) {
        return ResponseEntity.ok(service.currentPlan(courseId, studentId));
    }

    @Override
    @PreAuthorize("@scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<LearningPlan> generateLearningPlan(
            UUID courseId, UUID studentId, PlanGenerationRequest request) {
        LearningPlan plan = lifecycleService.regenerate(
                courseId, studentId, request.getReason().getValue());
        return ResponseEntity.created(URI.create(
                        "/api/v1/courses/" + courseId + "/students/" + studentId
                                + "/learning-plans/current"))
                .body(plan);
    }

    @Override
    @PreAuthorize("@scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<PlanLifecycle> getCurrentLearningPlanLifecycle(
            UUID courseId, UUID studentId) {
        return ResponseEntity.ok(PlanningApiMapper.toApi(
                lifecycleService.current(courseId, studentId)));
    }

    @Override
    @PreAuthorize("hasRole('STUDENT') and @scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<PlanLifecycle> startCurrentLearningPlanTask(
            UUID courseId,
            UUID studentId,
            UUID taskId,
            StartPlanTaskRequest request) {
        return ResponseEntity.ok(PlanningApiMapper.toApi(lifecycleService.startTask(
                principal(), courseId, studentId, taskId,
                new PlanningDtos.StartTaskRequest(request.getExpectedPlanId()))));
    }

    @Override
    @PreAuthorize("hasRole('STUDENT') and @scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<PlanLifecycle> skipCurrentLearningPlanTask(
            UUID courseId,
            UUID studentId,
            UUID taskId,
            SkipPlanTaskRequest request) {
        return ResponseEntity.ok(PlanningApiMapper.toApi(lifecycleService.skipTask(
                principal(), courseId, studentId, taskId,
                new PlanningDtos.SkipTaskRequest(request.getReason(), request.getExpectedPlanId()))));
    }

    @Override
    @PreAuthorize("@scopeAuthorization.canAccessStudent(authentication, #courseId, #studentId)")
    public ResponseEntity<Diagnosis> generateDiagnosis(
            UUID courseId, UUID studentId, Object request) {
        return ResponseEntity.ok(service.diagnosisForCurrentSnapshot(courseId, studentId));
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }
}
