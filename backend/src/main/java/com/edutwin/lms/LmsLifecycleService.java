package com.edutwin.lms;

import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.lms.LmsDtos.AssessmentCancelRequest;
import com.edutwin.lms.LmsDtos.AssessmentDetail;
import com.edutwin.lms.LmsDtos.AssessmentDueDateRequest;
import com.edutwin.lms.LmsDtos.AttemptRequest;
import com.edutwin.lms.LmsDtos.AttemptRequestCreate;
import com.edutwin.lms.LmsDtos.AttemptRequestDecision;
import com.edutwin.lms.LmsDtos.AttemptRequestList;
import com.edutwin.lms.LmsDtos.ContentOrderRequest;
import com.edutwin.lms.LmsDtos.LessonMutationRequest;
import com.edutwin.lms.LmsDtos.LifecycleImpactPreview;
import com.edutwin.lms.LmsDtos.LmsCourse;
import com.edutwin.lms.LmsDtos.LmsLesson;
import com.edutwin.lms.LmsDtos.LmsSection;
import com.edutwin.lms.LmsDtos.SectionMutationRequest;
import com.edutwin.lms.LmsDtos.StatusChangeRequest;
import com.edutwin.shared.web.DomainException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LmsLifecycleService {

    private static final Set<String> ATTEMPT_TYPES = Set.of("MAKEUP", "RETAKE", "APPEAL");
    private final JdbcClient jdbcClient;
    private final LmsService lmsService;
    private final LmsNotificationPublisher notifications;

    public LmsLifecycleService(
            JdbcClient jdbcClient,
            LmsService lmsService,
            LmsNotificationPublisher notifications) {
        this.jdbcClient = jdbcClient;
        this.lmsService = lmsService;
        this.notifications = notifications;
    }

    public LifecycleImpactPreview previewCourseStatus(
            EduTwinPrincipal principal, UUID courseId, StatusChangeRequest request) {
        requireCourseOwner(principal, courseId);
        String current = courseStatus(courseId);
        String target = requestedStatus(request);
        validateCourseTransition(current, target);
        return new LifecycleImpactPreview(
                "COURSE", courseId, current, target,
                count("SELECT COUNT(*) FROM lms_section WHERE course_id = :id AND status <> 'ARCHIVED'", courseId),
                count("SELECT COUNT(*) FROM lms_lesson l JOIN lms_section s ON s.id=l.section_id WHERE s.course_id=:id AND l.status <> 'ARCHIVED'", courseId),
                count("SELECT COUNT(*) FROM lms_assessment WHERE course_id=:id AND status <> 'ARCHIVED'", courseId),
                count("SELECT COUNT(*) FROM course_enrollment WHERE course_id=:id AND status='ACTIVE'", courseId),
                count("SELECT COUNT(*) FROM lms_submission x JOIN lms_assessment a ON a.id=x.assessment_id WHERE a.course_id=:id", courseId),
                !current.equals(target));
    }

    @Transactional
    public LmsCourse changeCourseStatus(
            EduTwinPrincipal principal, UUID courseId, StatusChangeRequest request) {
        LifecycleImpactPreview preview = previewCourseStatus(principal, courseId, request);
        requireExpected(request, preview.currentStatus());
        if (preview.currentStatus().equals(preview.targetStatus())) return lmsService.managedCourseView(principal, courseId);
        if ("PUBLISHED".equals(preview.targetStatus()) && count("""
                SELECT COUNT(*) FROM lms_lesson l JOIN lms_section s ON s.id=l.section_id
                WHERE s.course_id=:id AND s.status='PUBLISHED' AND l.status='PUBLISHED'
                """, courseId) == 0) {
            throw conflict("COURSE_CONTENT_REQUIRED", "A course requires at least one published lesson.");
        }
        jdbcClient.sql("UPDATE course SET status=:target WHERE id=:id AND status=:current")
                .param("target", preview.targetStatus()).param("id", courseId.toString())
                .param("current", preview.currentStatus()).update();
        insertCourseHistory(courseId, preview.currentStatus(), preview.targetStatus(), reason(request), principal.userId());
        return lmsService.managedCourseView(principal, courseId);
    }

    @Transactional
    public LmsSection updateSection(
            EduTwinPrincipal principal, UUID sectionId, SectionMutationRequest request) {
        UUID courseId = courseForSection(sectionId);
        requireTeacherCourse(principal, courseId);
        String current = contentStatus("lms_section", sectionId);
        if (!"DRAFT".equals(current)) throw conflict("CONTENT_LOCKED", "Only draft sections can be edited.");
        requireText(request == null ? null : request.title(), "Section title is required.");
        jdbcClient.sql("UPDATE lms_section SET title=:title, description=:description WHERE id=:id")
                .param("title", request.title().trim()).param("description", clean(request.description()))
                .param("id", sectionId.toString()).update();
        return section(sectionId);
    }

    @Transactional
    public LmsLesson updateLesson(
            EduTwinPrincipal principal, UUID lessonId, LessonMutationRequest request) {
        UUID courseId = courseForLesson(lessonId);
        requireTeacherCourse(principal, courseId);
        String current = contentStatus("lms_lesson", lessonId);
        if (!"DRAFT".equals(current)) throw conflict("CONTENT_LOCKED", "Only draft lessons can be edited.");
        requireText(request == null ? null : request.title(), "Lesson title is required.");
        requireText(request.body(), "Lesson body is required.");
        jdbcClient.sql("""
                UPDATE lms_lesson SET title=:title, summary=:summary, body=:body, resource_url=:resourceUrl
                WHERE id=:id
                """).param("title", request.title().trim()).param("summary", clean(request.summary()))
                .param("body", request.body().trim()).param("resourceUrl", blankToNull(request.resourceUrl()))
                .param("id", lessonId.toString()).update();
        return lesson(lessonId);
    }

    @Transactional
    public LmsSection changeSectionStatus(
            EduTwinPrincipal principal, UUID sectionId, StatusChangeRequest request) {
        UUID courseId = courseForSection(sectionId);
        requireTeacherCourse(principal, courseId);
        String current = contentStatus("lms_section", sectionId);
        String target = requestedStatus(request);
        requireExpected(request, current);
        validateContentTransition(current, target);
        if ("PUBLISHED".equals(target)
                && count("SELECT COUNT(*) FROM lms_lesson WHERE section_id=:id AND status='PUBLISHED'", sectionId) == 0) {
            throw conflict("SECTION_LESSON_REQUIRED", "A section requires at least one published lesson.");
        }
        changeContentStatus("SECTION", "lms_section", sectionId, courseId, current, target, reason(request), principal.userId());
        return section(sectionId);
    }

    @Transactional
    public LmsLesson changeLessonStatus(
            EduTwinPrincipal principal, UUID lessonId, StatusChangeRequest request) {
        UUID courseId = courseForLesson(lessonId);
        requireTeacherCourse(principal, courseId);
        String current = contentStatus("lms_lesson", lessonId);
        String target = requestedStatus(request);
        requireExpected(request, current);
        validateContentTransition(current, target);
        changeContentStatus("LESSON", "lms_lesson", lessonId, courseId, current, target, reason(request), principal.userId());
        return lesson(lessonId);
    }

    @Transactional
    public void reorderSections(EduTwinPrincipal principal, UUID courseId, ContentOrderRequest request) {
        requireTeacherCourse(principal, courseId);
        reorder("lms_section", "course_id", courseId, request);
    }

    @Transactional
    public void reorderLessons(EduTwinPrincipal principal, UUID sectionId, ContentOrderRequest request) {
        UUID courseId = courseForSection(sectionId);
        requireTeacherCourse(principal, courseId);
        reorder("lms_lesson", "section_id", sectionId, request);
    }

    @Transactional
    public AssessmentDetail extendAssessment(
            EduTwinPrincipal principal, UUID assessmentId, AssessmentDueDateRequest request) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        if (request == null || request.dueAt() == null) throw badRequest("ASSESSMENT_DUE_DATE_REQUIRED", "A due date is required.");
        AssessmentState current = assessmentState(assessmentId);
        if (Set.of("DRAFT", "CANCELLED", "ARCHIVED").contains(current.status())) {
            throw conflict("ASSESSMENT_EXTENSION_NOT_ALLOWED", "Only published or closed assessments can be extended.");
        }
        boolean reopen = "CLOSED".equals(current.status()) && request.dueAt().isAfter(now());
        jdbcClient.sql("""
                UPDATE lms_assessment SET due_at=:dueAt, status=:status,
                    closed_at=CASE WHEN :status='PUBLISHED' THEN NULL ELSE closed_at END
                WHERE id=:id
                """).param("dueAt", timestamp(request.dueAt()))
                .param("status", reopen ? "PUBLISHED" : current.status()).param("id", assessmentId.toString()).update();
        if (reopen) insertAssessmentHistory(assessmentId, "CLOSED", "PUBLISHED", cleanReason(request.reason()), principal.userId());
        notifyEnrolled(courseId, "ASSESSMENT_EXTENDED", "assessment:" + assessmentId + ":due:" + request.dueAt(), Map.of("assessmentId", assessmentId.toString()));
        return lmsService.teacherAssessmentView(principal, assessmentId);
    }

    @Transactional
    public AssessmentDetail cancelAssessment(
            EduTwinPrincipal principal, UUID assessmentId, AssessmentCancelRequest request) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        requireText(request == null ? null : request.reason(), "Cancellation reason is required.");
        AssessmentState current = assessmentState(assessmentId);
        if (!Set.of("PUBLISHED", "CLOSED").contains(current.status())) {
            throw conflict("ASSESSMENT_CANCELLATION_NOT_ALLOWED", "Only published or closed assessments can be cancelled.");
        }
        jdbcClient.sql("""
                UPDATE lms_assessment SET status='CANCELLED', cancelled_at=CURRENT_TIMESTAMP(6), cancellation_reason=:reason
                WHERE id=:id
                """).param("reason", request.reason().trim()).param("id", assessmentId.toString()).update();
        insertAssessmentHistory(assessmentId, current.status(), "CANCELLED", request.reason().trim(), principal.userId());
        notifyEnrolled(courseId, "ASSESSMENT_CANCELLED", "assessment:" + assessmentId + ":cancelled", Map.of("assessmentId", assessmentId.toString()));
        return lmsService.teacherAssessmentView(principal, assessmentId);
    }

    @Transactional
    public AttemptRequest createStudentAttemptRequest(
            EduTwinPrincipal principal, UUID assessmentId, AttemptRequestCreate request) {
        requireStudent(principal);
        UUID courseId = courseForAssessment(assessmentId);
        requireStudentCourse(principal, courseId);
        return createAttemptRequest(principal, assessmentId, principal.userId(), request, false);
    }

    @Transactional
    public AttemptRequest grantAttempt(
            EduTwinPrincipal principal, UUID assessmentId, AttemptRequestCreate request) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        if (request == null || request.studentId() == null) throw badRequest("STUDENT_REQUIRED", "A student is required.");
        requireEnrollment(courseId, request.studentId());
        return createAttemptRequest(principal, assessmentId, request.studentId(), request, true);
    }

    @Transactional
    public AttemptRequest decideAttemptRequest(
            EduTwinPrincipal principal, UUID requestId, AttemptRequestDecision request) {
        AttemptRequest current = attemptRequest(requestId);
        UUID courseId = courseForAssessment(current.assessmentId());
        requireTeacherCourse(principal, courseId);
        String decision = request == null ? null : clean(request.decision()).toUpperCase();
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) {
            throw badRequest("ATTEMPT_DECISION_INVALID", "Decision must be APPROVED or REJECTED.");
        }
        if (!"PENDING".equals(current.status())) throw conflict("ATTEMPT_REQUEST_DECIDED", "The request has already been decided.");
        if ("APPROVED".equals(decision)
                && (request.personalDueAt() == null || !request.personalDueAt().isAfter(now()))) {
            throw badRequest("PERSONAL_DUE_DATE_INVALID", "An approved attempt requires a future personal due date.");
        }
        int changed = jdbcClient.sql("""
                UPDATE lms_attempt_request SET status=:decision, personal_due_at=:dueAt,
                    decided_by=:actorId, decision_reason=:reason, decided_at=CURRENT_TIMESTAMP(6)
                WHERE id=:id AND status='PENDING'
                """).param("decision", decision).param("dueAt", timestamp(request.personalDueAt()))
                .param("actorId", principal.userId().toString()).param("reason", cleanReason(request.reason()))
                .param("id", requestId.toString()).update();
        if (changed != 1) throw conflict("ATTEMPT_REQUEST_DECIDED", "The request has already been decided.");
        notifications.publish(current.studentId(), "ATTEMPT_REQUEST_" + decision,
                "attempt-request:" + requestId + ":" + decision, Map.of(
                        "assessmentId", current.assessmentId().toString(),
                        "courseId", courseId.toString()));
        return attemptRequest(requestId);
    }

    public AttemptRequestList studentAttemptRequests(EduTwinPrincipal principal, UUID assessmentId) {
        requireStudent(principal);
        UUID courseId = courseForAssessment(assessmentId);
        requireStudentCourse(principal, courseId);
        List<AttemptRequest> items = jdbcClient.sql(attemptRequestSql() + " WHERE r.assessment_id=:assessmentId AND r.student_id=:studentId ORDER BY r.requested_at DESC")
                .param("assessmentId", assessmentId.toString()).param("studentId", principal.userId().toString())
                .query((rs, row) -> mapAttemptRequest(rs)).list();
        return new AttemptRequestList(items, items.size());
    }

    public AttemptRequestList teacherAttemptRequests(EduTwinPrincipal principal, UUID assessmentId) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        List<AttemptRequest> items = jdbcClient.sql(attemptRequestSql() + " WHERE r.assessment_id=:assessmentId ORDER BY r.requested_at DESC")
                .param("assessmentId", assessmentId.toString()).query((rs, row) -> mapAttemptRequest(rs)).list();
        return new AttemptRequestList(items, items.size());
    }

    @Scheduled(fixedDelayString = "${edutwin.lms.close-expired-delay:PT1M}")
    @Transactional
    public void closeExpiredAssessments() {
        List<UUID> expired = jdbcClient.sql("""
                SELECT id FROM lms_assessment
                WHERE status='PUBLISHED' AND due_at IS NOT NULL AND due_at <= CURRENT_TIMESTAMP(6)
                ORDER BY id FOR UPDATE
                """).query((rs, row) -> UUID.fromString(rs.getString("id"))).list();
        for (UUID assessmentId : expired) {
            jdbcClient.sql("UPDATE lms_assessment SET status='CLOSED', closed_at=CURRENT_TIMESTAMP(6) WHERE id=:id AND status='PUBLISHED'")
                    .param("id", assessmentId.toString()).update();
            insertAssessmentHistory(assessmentId, "PUBLISHED", "CLOSED", "DUE_DATE_REACHED", null);
        }
    }

    private AttemptRequest createAttemptRequest(
            EduTwinPrincipal principal,
            UUID assessmentId,
            UUID studentId,
            AttemptRequestCreate request,
            boolean approved) {
        if (request == null || !ATTEMPT_TYPES.contains(request.requestType())) {
            throw badRequest("ATTEMPT_REQUEST_TYPE_INVALID", "Request type must be MAKEUP, RETAKE, or APPEAL.");
        }
        requireText(request.reason(), "A request reason is required.");
        AssessmentState assessment = assessmentState(assessmentId);
        if (Set.of("CANCELLED", "ARCHIVED", "DRAFT").contains(assessment.status())) {
            throw conflict("ATTEMPT_REQUEST_NOT_ALLOWED", "The assessment does not accept attempt requests.");
        }
        int open = jdbcClient.sql("""
                SELECT COUNT(*) FROM lms_attempt_request
                WHERE assessment_id=:assessmentId AND student_id=:studentId AND request_type=:type
                  AND (status='PENDING' OR (status='APPROVED' AND consumed_at IS NULL))
                """).param("assessmentId", assessmentId.toString()).param("studentId", studentId.toString())
                .param("type", request.requestType()).query(Integer.class).single();
        if (open > 0) throw conflict("ATTEMPT_REQUEST_OPEN", "An open request of this type already exists.");
        if (approved && (request.requestedDueAt() == null || !request.requestedDueAt().isAfter(now()))) {
            throw badRequest("PERSONAL_DUE_DATE_INVALID", "A proactive grant requires a future personal due date.");
        }
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO lms_attempt_request(
                    id, assessment_id, student_id, request_type, status, reason, requested_due_at,
                    personal_due_at, requested_by, decided_by, decision_reason, decided_at)
                VALUES (:id,:assessmentId,:studentId,:type,:status,:reason,:requestedDueAt,
                    :personalDueAt,:requestedBy,:decidedBy,:decisionReason,:decidedAt)
                """).param("id", id.toString()).param("assessmentId", assessmentId.toString())
                .param("studentId", studentId.toString()).param("type", request.requestType())
                .param("status", approved ? "APPROVED" : "PENDING").param("reason", request.reason().trim())
                .param("requestedDueAt", timestamp(request.requestedDueAt()))
                .param("personalDueAt", approved ? timestamp(request.requestedDueAt()) : null)
                .param("requestedBy", principal.userId().toString())
                .param("decidedBy", approved ? principal.userId().toString() : null)
                .param("decisionReason", approved ? "TEACHER_PROACTIVE_GRANT" : null)
                .param("decidedAt", approved ? Timestamp.from(now().toInstant()) : null).update();
        if (approved) {
            notifications.publish(studentId, "ATTEMPT_GRANTED", "attempt-request:" + id + ":APPROVED",
                    Map.of(
                            "assessmentId", assessmentId.toString(),
                            "courseId", courseForAssessment(assessmentId).toString()));
        }
        return attemptRequest(id);
    }

    private void reorder(String table, String parentColumn, UUID parentId, ContentOrderRequest request) {
        if (request == null || request.itemIds() == null || request.itemIds().isEmpty()
                || request.itemIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(request.itemIds()).size() != request.itemIds().size()) {
            throw badRequest("CONTENT_ORDER_INVALID", "The order must contain each active item exactly once.");
        }
        List<UUID> existing = jdbcClient.sql("SELECT id FROM " + table + " WHERE " + parentColumn + "=:parentId AND status <> 'ARCHIVED' ORDER BY position")
                .param("parentId", parentId.toString()).query((rs, row) -> UUID.fromString(rs.getString("id"))).list();
        if (!new HashSet<>(existing).equals(new HashSet<>(request.itemIds()))) {
            throw badRequest("CONTENT_ORDER_INVALID", "The order must contain each active item exactly once.");
        }
        jdbcClient.sql("UPDATE " + table + " SET position=position+1000000 WHERE " + parentColumn + "=:parentId AND status <> 'ARCHIVED'")
                .param("parentId", parentId.toString()).update();
        for (int index = 0; index < request.itemIds().size(); index++) {
            jdbcClient.sql("UPDATE " + table + " SET position=:position WHERE id=:id")
                    .param("position", index + 1).param("id", request.itemIds().get(index).toString()).update();
        }
    }

    private void changeContentStatus(
            String type, String table, UUID entityId, UUID courseId, String current, String target,
            String reason, UUID actorId) {
        if (current.equals(target)) return;
        jdbcClient.sql("UPDATE " + table + " SET status=:target WHERE id=:id AND status=:current")
                .param("target", target).param("id", entityId.toString()).param("current", current).update();
        jdbcClient.sql("""
                INSERT INTO lms_content_status_history(
                    id,entity_type,entity_id,course_id,previous_status,target_status,reason,actor_id)
                VALUES (:id,:type,:entityId,:courseId,:current,:target,:reason,:actorId)
                """).param("id", UUID.randomUUID().toString()).param("type", type)
                .param("entityId", entityId.toString()).param("courseId", courseId.toString())
                .param("current", current).param("target", target).param("reason", reason)
                .param("actorId", actorId.toString()).update();
    }

    private void insertCourseHistory(UUID courseId, String current, String target, String reason, UUID actorId) {
        jdbcClient.sql("""
                INSERT INTO lms_course_status_history(id,course_id,previous_status,target_status,reason,actor_id)
                VALUES (:id,:courseId,:current,:target,:reason,:actorId)
                """).param("id", UUID.randomUUID().toString()).param("courseId", courseId.toString())
                .param("current", current).param("target", target).param("reason", reason)
                .param("actorId", actorId.toString()).update();
    }

    private void insertAssessmentHistory(
            UUID assessmentId, String current, String target, String reason, UUID actorId) {
        jdbcClient.sql("""
                INSERT INTO lms_assessment_status_history(
                    id,assessment_id,previous_status,target_status,reason,actor_id)
                VALUES (:id,:assessmentId,:current,:target,:reason,:actorId)
                """).param("id", UUID.randomUUID().toString()).param("assessmentId", assessmentId.toString())
                .param("current", current).param("target", target).param("reason", reason)
                .param("actorId", actorId == null ? null : actorId.toString()).update();
    }

    private AttemptRequest attemptRequest(UUID requestId) {
        return jdbcClient.sql(attemptRequestSql() + " WHERE r.id=:id")
                .param("id", requestId.toString()).query((rs, row) -> mapAttemptRequest(rs)).optional()
                .orElseThrow(() -> notFound("ATTEMPT_REQUEST_NOT_FOUND", "The attempt request does not exist."));
    }

    private static String attemptRequestSql() {
        return """
                SELECT r.*, EXISTS(SELECT 1 FROM lms_submission s WHERE s.grant_request_id=r.id) consumed
                FROM lms_attempt_request r
                """;
    }

    private static AttemptRequest mapAttemptRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AttemptRequest(
                uuid(rs.getString("id")), uuid(rs.getString("assessment_id")), uuid(rs.getString("student_id")),
                rs.getString("request_type"), rs.getString("status"), rs.getString("reason"),
                offset(rs.getTimestamp("requested_due_at")), offset(rs.getTimestamp("personal_due_at")),
                uuid(rs.getString("requested_by")), uuid(rs.getString("decided_by")), rs.getString("decision_reason"),
                offset(rs.getTimestamp("requested_at")), offset(rs.getTimestamp("decided_at")), rs.getBoolean("consumed"));
    }

    private LmsSection section(UUID sectionId) {
        return jdbcClient.sql("SELECT id,title,description,position,status FROM lms_section WHERE id=:id")
                .param("id", sectionId.toString()).query((rs, row) -> new LmsSection(
                        uuid(rs.getString("id")), rs.getString("title"), rs.getString("description"),
                        rs.getInt("position"), rs.getString("status"), List.of())).single();
    }

    private LmsLesson lesson(UUID lessonId) {
        return jdbcClient.sql("SELECT id,title,summary,body,resource_url,position,status FROM lms_lesson WHERE id=:id")
                .param("id", lessonId.toString()).query((rs, row) -> new LmsLesson(
                        uuid(rs.getString("id")), rs.getString("title"), rs.getString("summary"), rs.getString("body"),
                        rs.getString("resource_url"), rs.getInt("position"), rs.getString("status"), false)).single();
    }

    private String courseStatus(UUID courseId) {
        return jdbcClient.sql("SELECT status FROM course WHERE id=:id").param("id", courseId.toString())
                .query(String.class).optional().orElseThrow(() -> notFound("COURSE_NOT_FOUND", "The course does not exist."));
    }

    private String contentStatus(String table, UUID id) {
        return jdbcClient.sql("SELECT status FROM " + table + " WHERE id=:id").param("id", id.toString())
                .query(String.class).optional().orElseThrow(() -> notFound("CONTENT_NOT_FOUND", "The content does not exist."));
    }

    private AssessmentState assessmentState(UUID id) {
        return jdbcClient.sql("SELECT status,due_at FROM lms_assessment WHERE id=:id").param("id", id.toString())
                .query((rs, row) -> new AssessmentState(rs.getString("status"), offset(rs.getTimestamp("due_at"))))
                .optional().orElseThrow(() -> notFound("ASSESSMENT_NOT_FOUND", "The assessment does not exist."));
    }

    private UUID courseForSection(UUID id) {
        return foreignId("SELECT course_id FROM lms_section WHERE id=:id", id, "SECTION_NOT_FOUND");
    }

    private UUID courseForLesson(UUID id) {
        return foreignId("SELECT s.course_id FROM lms_lesson l JOIN lms_section s ON s.id=l.section_id WHERE l.id=:id", id, "LESSON_NOT_FOUND");
    }

    private UUID courseForAssessment(UUID id) {
        return foreignId("SELECT course_id FROM lms_assessment WHERE id=:id", id, "ASSESSMENT_NOT_FOUND");
    }

    private UUID foreignId(String sql, UUID id, String code) {
        return jdbcClient.sql(sql).param("id", id.toString()).query((rs, row) -> uuid(rs.getString(1))).optional()
                .orElseThrow(() -> notFound(code, "The requested resource does not exist."));
    }

    private void requireCourseOwner(EduTwinPrincipal principal, UUID courseId) {
        requireTeacher(principal);
        boolean allowed = jdbcClient.sql("""
                SELECT EXISTS(SELECT 1 FROM teaching_assignment
                  WHERE course_id=:courseId AND teacher_id=:teacherId AND assignment_role='OWNER') allowed
                """).param("courseId", courseId.toString()).param("teacherId", principal.userId().toString())
                .query((rs, row) -> rs.getBoolean("allowed")).single();
        if (!allowed) throw forbidden("COURSE_OWNER_REQUIRED", "Only the course owner may perform this operation.");
    }

    private void requireTeacherCourse(EduTwinPrincipal principal, UUID courseId) {
        requireTeacher(principal);
        boolean allowed = jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM teaching_assignment WHERE course_id=:courseId AND teacher_id=:teacherId) allowed")
                .param("courseId", courseId.toString()).param("teacherId", principal.userId().toString())
                .query((rs, row) -> rs.getBoolean("allowed")).single();
        if (!allowed) throw forbidden("COURSE_ACCESS_DENIED", "The teacher is not assigned to this course.");
    }

    private void requireStudentCourse(EduTwinPrincipal principal, UUID courseId) {
        requireStudent(principal);
        boolean allowed = jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM course_enrollment WHERE course_id=:courseId AND student_id=:studentId AND status='ACTIVE') allowed")
                .param("courseId", courseId.toString()).param("studentId", principal.userId().toString())
                .query((rs, row) -> rs.getBoolean("allowed")).single();
        if (!allowed) throw forbidden("COURSE_ACCESS_DENIED", "An active enrollment is required.");
    }

    private void requireEnrollment(UUID courseId, UUID studentId) {
        int found = jdbcClient.sql("SELECT COUNT(*) FROM course_enrollment WHERE course_id=:courseId AND student_id=:studentId AND status='ACTIVE'")
                .param("courseId", courseId.toString()).param("studentId", studentId.toString()).query(Integer.class).single();
        if (found == 0) throw notFound("ENROLLMENT_NOT_FOUND", "The student is not actively enrolled.");
    }

    private void notifyEnrolled(UUID courseId, String eventType, String dedupeKey, Map<String, String> attributes) {
        jdbcClient.sql("SELECT student_id FROM course_enrollment WHERE course_id=:courseId AND status='ACTIVE'")
                .param("courseId", courseId.toString()).query((rs, row) -> uuid(rs.getString("student_id"))).list()
                .forEach(studentId -> notifications.publish(studentId, eventType, dedupeKey + ":" + studentId, attributes));
    }

    private static void validateCourseTransition(String current, String target) {
        boolean valid = switch (current) {
            case "DRAFT" -> "PUBLISHED".equals(target);
            case "PUBLISHED" -> "ARCHIVED".equals(target);
            case "ARCHIVED" -> "DRAFT".equals(target);
            default -> false;
        };
        if (!current.equals(target) && !valid) throw conflict("COURSE_STATUS_TRANSITION_INVALID", "The requested course status transition is not allowed.");
    }

    private static void validateContentTransition(String current, String target) {
        boolean valid = switch (current) {
            case "DRAFT" -> Set.of("PUBLISHED", "ARCHIVED").contains(target);
            case "PUBLISHED" -> Set.of("DRAFT", "ARCHIVED").contains(target);
            case "ARCHIVED" -> "DRAFT".equals(target);
            default -> false;
        };
        if (!current.equals(target) && !valid) throw conflict("CONTENT_STATUS_TRANSITION_INVALID", "The requested content status transition is not allowed.");
    }

    private static void requireExpected(StatusChangeRequest request, String current) {
        if (request != null && request.expectedCurrentStatus() != null
                && !request.expectedCurrentStatus().equals(current)) {
            throw conflict("STATUS_CHANGED", "The resource status changed before this request was applied.");
        }
    }

    private static String requestedStatus(StatusChangeRequest request) {
        if (request == null || request.requestedStatus() == null) throw badRequest("STATUS_REQUIRED", "A target status is required.");
        return request.requestedStatus().trim().toUpperCase();
    }

    private static String reason(StatusChangeRequest request) {
        return cleanReason(request == null ? null : request.reason());
    }

    private static String cleanReason(String value) {
        return value == null || value.isBlank() ? "UNSPECIFIED" : value.trim();
    }

    private int count(String sql, UUID id) {
        return jdbcClient.sql(sql).param("id", id.toString()).query(Integer.class).single();
    }

    private static void requireTeacher(EduTwinPrincipal principal) {
        if (principal == null || !principal.enabled() || !"TEACHER".equals(principal.role().getValue())) {
            throw forbidden("TEACHER_ROLE_REQUIRED", "An enabled teacher is required.");
        }
    }

    private static void requireStudent(EduTwinPrincipal principal) {
        if (principal == null || !principal.enabled() || !"STUDENT".equals(principal.role().getValue())) {
            throw forbidden("STUDENT_ROLE_REQUIRED", "An enabled student is required.");
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) throw badRequest("VALIDATION_FAILED", message);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : OffsetDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static DomainException badRequest(String code, String message) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static DomainException forbidden(String code, String message) {
        return new DomainException(HttpStatus.FORBIDDEN, code, message);
    }

    private static DomainException notFound(String code, String message) {
        return new DomainException(HttpStatus.NOT_FOUND, code, message);
    }

    private static DomainException conflict(String code, String message) {
        return new DomainException(HttpStatus.CONFLICT, code, message);
    }

    private record AssessmentState(String status, OffsetDateTime dueAt) {}
}
