package com.edutwin.lms;

import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.lms.LmsDtos.AssessmentDetail;
import com.edutwin.lms.LmsDtos.AssessmentAttemptList;
import com.edutwin.lms.LmsDtos.AssessmentCancelRequest;
import com.edutwin.lms.LmsDtos.AssessmentCloneRequest;
import com.edutwin.lms.LmsDtos.AssessmentDueDateRequest;
import com.edutwin.lms.LmsDtos.AssessmentList;
import com.edutwin.lms.LmsDtos.AssessmentMutationRequest;
import com.edutwin.lms.LmsDtos.AssessmentResult;
import com.edutwin.lms.LmsDtos.AssessmentSubmissionRequest;
import com.edutwin.lms.LmsDtos.CourseMutationRequest;
import com.edutwin.lms.LmsDtos.CourseOutline;
import com.edutwin.lms.LmsDtos.ContentOrderRequest;
import com.edutwin.lms.LmsDtos.EnrollmentUpdateRequest;
import com.edutwin.lms.LmsDtos.GradeList;
import com.edutwin.lms.LmsDtos.AttemptRequest;
import com.edutwin.lms.LmsDtos.AttemptRequestCreate;
import com.edutwin.lms.LmsDtos.AttemptRequestDecision;
import com.edutwin.lms.LmsDtos.AttemptRequestList;
import com.edutwin.lms.LmsDtos.LessonMutationRequest;
import com.edutwin.lms.LmsDtos.LmsCourse;
import com.edutwin.lms.LmsDtos.LmsCourseList;
import com.edutwin.lms.LmsDtos.LmsLesson;
import com.edutwin.lms.LmsDtos.LmsSection;
import com.edutwin.lms.LmsDtos.LifecycleImpactPreview;
import com.edutwin.lms.LmsDtos.KnowledgeSkillList;
import com.edutwin.lms.LmsDtos.RosterList;
import com.edutwin.lms.LmsDtos.SectionMutationRequest;
import com.edutwin.lms.LmsDtos.StatusChangeRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lms")
public class LmsController {

    private final LmsService service;
    private final LmsLifecycleService lifecycleService;

    public LmsController(LmsService service, LmsLifecycleService lifecycleService) {
        this.service = service;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/teacher/courses")
    @PreAuthorize("hasRole('TEACHER')")
    public LmsCourseList listManagedCourses() {
        return service.listManagedCourses(principal());
    }

    @PostMapping("/teacher/courses")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<LmsCourse> createManagedCourse(@RequestBody CourseMutationRequest request) {
        LmsCourse created = service.createCourse(principal(), request);
        return ResponseEntity.created(URI.create("/api/v1/lms/teacher/courses/" + created.courseId()))
                .body(created);
    }

    @PutMapping("/teacher/courses/{courseId}")
    @PreAuthorize("hasRole('TEACHER')")
    public LmsCourse updateManagedCourse(
            @PathVariable UUID courseId, @RequestBody CourseMutationRequest request) {
        return service.updateCourse(principal(), courseId, request);
    }

    @PutMapping("/teacher/courses/{courseId}/status")
    @PreAuthorize("hasRole('TEACHER')")
    public LmsCourse changeManagedCourseStatus(
            @PathVariable UUID courseId, @RequestBody StatusChangeRequest request) {
        return lifecycleService.changeCourseStatus(principal(), courseId, request);
    }

    @PostMapping("/teacher/courses/{courseId}/status/preview")
    @PreAuthorize("hasRole('TEACHER')")
    public LifecycleImpactPreview previewManagedCourseStatus(
            @PathVariable UUID courseId, @RequestBody StatusChangeRequest request) {
        return lifecycleService.previewCourseStatus(principal(), courseId, request);
    }

    @GetMapping("/teacher/courses/{courseId}/sections")
    @PreAuthorize("hasRole('TEACHER')")
    public CourseOutline getManagedCourseOutline(@PathVariable UUID courseId) {
        return service.getOutline(principal(), courseId, true);
    }

    @PostMapping("/teacher/courses/{courseId}/sections")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<LmsSection> createCourseSection(
            @PathVariable UUID courseId, @RequestBody SectionMutationRequest request) {
        LmsSection created = service.createSection(principal(), courseId, request);
        return ResponseEntity.created(URI.create("/api/v1/lms/teacher/sections/" + created.sectionId()))
                .body(created);
    }

    @PostMapping("/teacher/sections/{sectionId}/lessons")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<LmsLesson> createSectionLesson(
            @PathVariable UUID sectionId, @RequestBody LessonMutationRequest request) {
        LmsLesson created = service.createLesson(principal(), sectionId, request);
        return ResponseEntity.created(URI.create("/api/v1/lms/teacher/lessons/" + created.lessonId()))
                .body(created);
    }

    @PutMapping("/teacher/sections/{sectionId}")
    @PreAuthorize("hasRole('TEACHER')")
    public LmsSection updateSection(
            @PathVariable UUID sectionId, @RequestBody SectionMutationRequest request) {
        return lifecycleService.updateSection(principal(), sectionId, request);
    }

    @PutMapping("/teacher/sections/{sectionId}/status")
    @PreAuthorize("hasRole('TEACHER')")
    public LmsSection changeSectionStatus(
            @PathVariable UUID sectionId, @RequestBody StatusChangeRequest request) {
        return lifecycleService.changeSectionStatus(principal(), sectionId, request);
    }

    @PutMapping("/teacher/lessons/{lessonId}")
    @PreAuthorize("hasRole('TEACHER')")
    public LmsLesson updateLesson(
            @PathVariable UUID lessonId, @RequestBody LessonMutationRequest request) {
        return lifecycleService.updateLesson(principal(), lessonId, request);
    }

    @PutMapping("/teacher/lessons/{lessonId}/status")
    @PreAuthorize("hasRole('TEACHER')")
    public LmsLesson changeLessonStatus(
            @PathVariable UUID lessonId, @RequestBody StatusChangeRequest request) {
        return lifecycleService.changeLessonStatus(principal(), lessonId, request);
    }

    @PutMapping("/teacher/courses/{courseId}/sections/order")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<Void> reorderSections(
            @PathVariable UUID courseId, @RequestBody ContentOrderRequest request) {
        lifecycleService.reorderSections(principal(), courseId, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/teacher/sections/{sectionId}/lessons/order")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<Void> reorderLessons(
            @PathVariable UUID sectionId, @RequestBody ContentOrderRequest request) {
        lifecycleService.reorderLessons(principal(), sectionId, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/teacher/courses/{courseId}/roster")
    @PreAuthorize("hasRole('TEACHER')")
    public RosterList getCourseRoster(@PathVariable UUID courseId) {
        return service.roster(principal(), courseId);
    }

    @GetMapping("/teacher/courses/{courseId}/knowledge-skills")
    @PreAuthorize("hasRole('TEACHER')")
    public KnowledgeSkillList getCourseKnowledgeSkills(@PathVariable UUID courseId) {
        return service.knowledgeSkills(principal(), courseId);
    }

    @PutMapping("/teacher/courses/{courseId}/roster/{studentId}")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<Void> updateCourseEnrollment(
            @PathVariable UUID courseId,
            @PathVariable UUID studentId,
            @RequestBody EnrollmentUpdateRequest request) {
        service.updateEnrollment(principal(), courseId, studentId, request.enrolled(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/teacher/courses/{courseId}/assessments")
    @PreAuthorize("hasRole('TEACHER')")
    public AssessmentList listManagedAssessments(@PathVariable UUID courseId) {
        return service.listAssessments(principal(), courseId, true);
    }

    @PostMapping("/teacher/courses/{courseId}/assessments")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<AssessmentDetail> createAssessment(
            @PathVariable UUID courseId, @RequestBody AssessmentMutationRequest request) {
        AssessmentDetail created = service.createAssessment(principal(), courseId, request);
        return ResponseEntity.created(URI.create("/api/v1/lms/teacher/assessments/" + created.assessmentId()))
                .body(created);
    }

    @PutMapping("/teacher/assessments/{assessmentId}/status")
    @PreAuthorize("hasRole('TEACHER')")
    public AssessmentDetail changeAssessmentStatus(
            @PathVariable UUID assessmentId, @RequestBody StatusChangeRequest request) {
        return service.changeAssessmentStatus(principal(), assessmentId, request);
    }

    @PutMapping("/teacher/assessments/{assessmentId}")
    @PreAuthorize("hasRole('TEACHER')")
    public AssessmentDetail updateAssessment(
            @PathVariable UUID assessmentId, @RequestBody AssessmentMutationRequest request) {
        return service.updateAssessment(principal(), assessmentId, request);
    }

    @PostMapping("/teacher/assessments/{assessmentId}/extend")
    @PreAuthorize("hasRole('TEACHER')")
    public AssessmentDetail extendAssessment(
            @PathVariable UUID assessmentId, @RequestBody AssessmentDueDateRequest request) {
        return lifecycleService.extendAssessment(principal(), assessmentId, request);
    }

    @PostMapping("/teacher/assessments/{assessmentId}/cancel")
    @PreAuthorize("hasRole('TEACHER')")
    public AssessmentDetail cancelAssessment(
            @PathVariable UUID assessmentId, @RequestBody AssessmentCancelRequest request) {
        return lifecycleService.cancelAssessment(principal(), assessmentId, request);
    }

    @PostMapping("/teacher/assessments/{assessmentId}/clone")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<AssessmentDetail> cloneAssessment(
            @PathVariable UUID assessmentId, @RequestBody AssessmentCloneRequest request) {
        AssessmentDetail created = service.cloneAssessment(principal(), assessmentId, request);
        return ResponseEntity.created(URI.create("/api/v1/lms/teacher/assessments/" + created.assessmentId())).body(created);
    }

    @GetMapping("/teacher/assessments/{assessmentId}/attempt-requests")
    @PreAuthorize("hasRole('TEACHER')")
    public AttemptRequestList listAttemptRequests(@PathVariable UUID assessmentId) {
        return lifecycleService.teacherAttemptRequests(principal(), assessmentId);
    }

    @PostMapping("/teacher/assessments/{assessmentId}/attempt-grants")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<AttemptRequest> grantAttempt(
            @PathVariable UUID assessmentId, @RequestBody AttemptRequestCreate request) {
        return ResponseEntity.status(201).body(lifecycleService.grantAttempt(principal(), assessmentId, request));
    }

    @PutMapping("/teacher/attempt-requests/{requestId}/decision")
    @PreAuthorize("hasRole('TEACHER')")
    public AttemptRequest decideAttemptRequest(
            @PathVariable UUID requestId, @RequestBody AttemptRequestDecision request) {
        return lifecycleService.decideAttemptRequest(principal(), requestId, request);
    }

    @GetMapping("/teacher/assessments/{assessmentId}/submissions")
    @PreAuthorize("hasRole('TEACHER')")
    public GradeList listAssessmentSubmissions(@PathVariable UUID assessmentId) {
        return service.assessmentGrades(principal(), assessmentId);
    }

    @GetMapping("/courses/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    public CourseOutline getPublishedCourse(@PathVariable UUID courseId) {
        return service.getOutline(principal(), courseId, false);
    }

    @PutMapping("/courses/{courseId}/lessons/{lessonId}/completion")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> completeLesson(
            @PathVariable UUID courseId, @PathVariable UUID lessonId) {
        service.completeLesson(principal(), courseId, lessonId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/courses/{courseId}/assessments")
    @PreAuthorize("hasRole('STUDENT')")
    public AssessmentList listPublishedAssessments(@PathVariable UUID courseId) {
        return service.listAssessments(principal(), courseId, false);
    }

    @GetMapping("/assessments/{assessmentId}")
    @PreAuthorize("hasRole('STUDENT')")
    public AssessmentDetail getPublishedAssessment(@PathVariable UUID assessmentId) {
        return service.getAssessment(principal(), assessmentId);
    }

    @PostMapping("/assessments/{assessmentId}/submissions")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<AssessmentResult> submitAssessment(
            @PathVariable UUID assessmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AssessmentSubmissionRequest request) {
        return ResponseEntity.status(201)
                .body(service.submitAssessment(principal(), assessmentId, idempotencyKey, request));
    }

    @GetMapping("/assessments/{assessmentId}/submissions")
    @PreAuthorize("hasRole('STUDENT')")
    public AssessmentResult getOwnAssessmentSubmission(@PathVariable UUID assessmentId) {
        return service.ownAssessmentSubmission(principal(), assessmentId);
    }

    @GetMapping("/assessments/{assessmentId}/attempts")
    @PreAuthorize("hasRole('STUDENT')")
    public AssessmentAttemptList getOwnAssessmentAttempts(@PathVariable UUID assessmentId) {
        return service.ownAssessmentAttempts(principal(), assessmentId);
    }

    @GetMapping("/assessments/{assessmentId}/attempt-requests")
    @PreAuthorize("hasRole('STUDENT')")
    public AttemptRequestList getOwnAttemptRequests(@PathVariable UUID assessmentId) {
        return lifecycleService.studentAttemptRequests(principal(), assessmentId);
    }

    @PostMapping("/assessments/{assessmentId}/attempt-requests")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<AttemptRequest> requestAttempt(
            @PathVariable UUID assessmentId, @RequestBody AttemptRequestCreate request) {
        return ResponseEntity.status(201).body(lifecycleService.createStudentAttemptRequest(principal(), assessmentId, request));
    }

    @GetMapping("/courses/{courseId}/grades")
    @PreAuthorize("hasRole('STUDENT')")
    public GradeList getStudentGrades(@PathVariable UUID courseId) {
        return service.studentGrades(principal(), courseId);
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
