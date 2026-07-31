package com.edutwin.learning;

import com.edutwin.api.PracticeApi;
import com.edutwin.api.model.AnalysisJob;
import com.edutwin.api.model.AnswerSubmission;
import com.edutwin.api.model.AnswerHistoryPage;
import com.edutwin.api.model.PracticeQuestion;
import com.edutwin.api.model.PlanPracticeTask;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.planning.PlanningApiMapper;
import com.edutwin.planning.PlanningLifecycleService;
import com.edutwin.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnswerSubmissionController implements PracticeApi {

    private final AnswerSubmissionService service;
    private final PlanningLifecycleService planningLifecycleService;
    private final HttpServletRequest request;

    public AnswerSubmissionController(
            AnswerSubmissionService service,
            PlanningLifecycleService planningLifecycleService,
            HttpServletRequest request) {
        this.service = service;
        this.planningLifecycleService = planningLifecycleService;
        this.request = request;
    }

    @Override
    @PreAuthorize("hasRole('STUDENT') and @scopeAuthorization.canAccessCourse(authentication, #courseId)")
    public ResponseEntity<PlanPracticeTask> getLearningPlanPracticeTask(
            UUID courseId, UUID taskId) {
        return ResponseEntity.ok(PlanningApiMapper.toApi(
                planningLifecycleService.practiceTask(principal(), courseId, taskId)));
    }

    @Override
    @PreAuthorize("hasRole('STUDENT') and @scopeAuthorization.canAccessCourse(authentication, #courseId)")
    public ResponseEntity<PracticeQuestion> getNextPracticeQuestion(UUID courseId) {
        return ResponseEntity.ok(service.nextQuestion(principal(), courseId));
    }

    @Override
    @PreAuthorize("hasRole('STUDENT') and @scopeAuthorization.canAccessCourse(authentication, #courseId)")
    public ResponseEntity<AnswerHistoryPage> listAnswerHistory(
            UUID courseId, Integer page, Integer size) {
        return ResponseEntity.ok(service.history(principal(), courseId, page, size));
    }

    @Override
    @PreAuthorize("hasRole('STUDENT') and @scopeAuthorization.canAccessCourse(authentication, #courseId)")
    public ResponseEntity<AnalysisJob> submitAnswer(
            String idempotencyKey, UUID courseId, AnswerSubmission answerSubmission) {
        AnswerSubmissionResult result = service.submit(
                principal(),
                courseId,
                idempotencyKey,
                answerSubmission,
                UUID.fromString(CorrelationIdFilter.from(request)));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/api/v1/analysis/jobs/" + result.job().getJobId())
                .header("Retry-After", "1")
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.job());
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }
}
