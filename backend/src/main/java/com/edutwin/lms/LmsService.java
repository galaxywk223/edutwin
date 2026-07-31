package com.edutwin.lms;

import com.edutwin.api.model.AnswerSubmission;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.dashboard.TeacherDashboardService;
import com.edutwin.learning.AnswerSubmissionService;
import com.edutwin.lms.LmsDtos.AssessmentAnswerRequest;
import com.edutwin.lms.LmsDtos.AssessmentAnswerResult;
import com.edutwin.lms.LmsDtos.AssessmentAttemptList;
import com.edutwin.lms.LmsDtos.AssessmentCloneRequest;
import com.edutwin.lms.LmsDtos.AssessmentDetail;
import com.edutwin.lms.LmsDtos.AssessmentList;
import com.edutwin.lms.LmsDtos.AssessmentMutationRequest;
import com.edutwin.lms.LmsDtos.AssessmentOption;
import com.edutwin.lms.LmsDtos.AssessmentQuestion;
import com.edutwin.lms.LmsDtos.AssessmentQuestionRequest;
import com.edutwin.lms.LmsDtos.AssessmentResult;
import com.edutwin.lms.LmsDtos.AssessmentSubmissionRequest;
import com.edutwin.lms.LmsDtos.AssessmentSummary;
import com.edutwin.lms.LmsDtos.CourseMutationRequest;
import com.edutwin.lms.LmsDtos.CourseOutline;
import com.edutwin.lms.LmsDtos.GradeItem;
import com.edutwin.lms.LmsDtos.GradeList;
import com.edutwin.lms.LmsDtos.LessonMutationRequest;
import com.edutwin.lms.LmsDtos.LmsCourse;
import com.edutwin.lms.LmsDtos.LmsCourseList;
import com.edutwin.lms.LmsDtos.LmsLesson;
import com.edutwin.lms.LmsDtos.LmsSection;
import com.edutwin.lms.LmsDtos.KnowledgeSkillList;
import com.edutwin.lms.LmsDtos.KnowledgeSkillSummary;
import com.edutwin.lms.LmsDtos.RosterList;
import com.edutwin.lms.LmsDtos.RosterStudent;
import com.edutwin.lms.LmsDtos.SectionMutationRequest;
import com.edutwin.shared.web.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LmsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LmsService.class);
    private static final Set<String> CONTENT_STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final Set<String> ASSESSMENT_STATUSES = Set.of("DRAFT", "PUBLISHED", "CLOSED", "CANCELLED", "ARCHIVED");
    private static final Set<String> ASSESSMENT_TYPES = Set.of("ASSIGNMENT", "QUIZ");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AnswerSubmissionService answerSubmissionService;
    private final LmsNotificationPublisher notifications;
    private final TeacherDashboardService teacherDashboardService;
    private final TransactionTemplate transactionTemplate;

    public LmsService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AnswerSubmissionService answerSubmissionService,
            LmsNotificationPublisher notifications,
            TeacherDashboardService teacherDashboardService,
            PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.answerSubmissionService = answerSubmissionService;
        this.notifications = notifications;
        this.teacherDashboardService = teacherDashboardService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public LmsCourseList listManagedCourses(EduTwinPrincipal principal) {
        requireTeacher(principal);
        List<LmsCourse> items = jdbcClient.sql("""
                SELECT c.id, c.code, c.title, c.term_label, c.description, c.status, c.starts_on,
                       c.college, c.department, c.credits, u.display_name instructor_name,
                       ta.assignment_role,
                       (SELECT COUNT(*) FROM lms_section s WHERE s.course_id = c.id AND s.status <> 'ARCHIVED') section_count,
                       (SELECT COUNT(*) FROM lms_assessment a WHERE a.course_id = c.id AND a.status <> 'ARCHIVED') assessment_count,
                       (SELECT COUNT(*) FROM course_enrollment e WHERE e.course_id = c.id AND e.status = 'ACTIVE') enrolled_count
                FROM course c
                JOIN teaching_assignment ta ON ta.course_id = c.id
                JOIN user_account u ON u.id = ta.teacher_id
                WHERE ta.teacher_id = :teacherId
                ORDER BY c.updated_at DESC, c.code
                """)
                .param("teacherId", principal.userId().toString())
                .query((rs, row) -> course(rs))
                .list();
        return new LmsCourseList(items, items.size());
    }

    @Transactional
    public LmsCourse createCourse(EduTwinPrincipal principal, CourseMutationRequest request) {
        requireTeacher(principal);
        validateCourse(request);
        UUID courseId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO course(
                    id, code, title, term_label, starts_on, data_version,
                    description, status, college, department, credits)
                SELECT :id, :code, :title, :termLabel, :startsOn, 'lms-authoring-v1',
                       :description, 'DRAFT', tp.college, tp.department, :credits
                FROM teacher_profile tp WHERE tp.user_id = :teacherId
                """)
                .param("id", courseId.toString())
                .param("code", request.code().trim())
                .param("title", request.title().trim())
                .param("termLabel", clean(request.termLabel()))
                .param("startsOn", Date.valueOf(request.startsOn()))
                .param("description", clean(request.description()))
                .param("credits", request.credits())
                .param("teacherId", principal.userId().toString())
                .update();
        jdbcClient.sql("""
                        INSERT INTO teaching_assignment(course_id, teacher_id, assignment_role)
                        VALUES (:courseId, :teacherId, 'OWNER')
                        """)
                .param("courseId", courseId.toString())
                .param("teacherId", principal.userId().toString())
                .update();
        jdbcClient.sql("""
                INSERT INTO lms_course_status_history(
                    id,course_id,previous_status,target_status,reason,actor_id)
                VALUES (:id,:courseId,NULL,'DRAFT','COURSE_CREATED',:actorId)
                """).param("id", UUID.randomUUID().toString()).param("courseId", courseId.toString())
                .param("actorId", principal.userId().toString()).update();
        return managedCourse(principal, courseId);
    }

    @Transactional
    public LmsCourse updateCourse(
            EduTwinPrincipal principal, UUID courseId, CourseMutationRequest request) {
        requireCourseOwner(principal, courseId);
        validateCourse(request);
        jdbcClient.sql("""
                UPDATE course
                SET code = :code, title = :title, term_label = :termLabel,
                    credits = :credits, description = :description, starts_on = :startsOn
                WHERE id = :courseId
                """)
                .param("courseId", courseId.toString())
                .param("code", request.code().trim())
                .param("title", request.title().trim())
                .param("termLabel", clean(request.termLabel()))
                .param("credits", request.credits())
                .param("description", clean(request.description()))
                .param("startsOn", Date.valueOf(request.startsOn()))
                .update();
        return managedCourse(principal, courseId);
    }

    @Transactional
    public LmsCourse changeCourseStatus(EduTwinPrincipal principal, UUID courseId, String status) {
        requireCourseOwner(principal, courseId);
        String normalized = requireContentStatus(status);
        if ("PUBLISHED".equals(normalized)) {
            int lessons = count("""
                    SELECT COUNT(*) FROM lms_lesson l
                    JOIN lms_section s ON s.id = l.section_id
                    WHERE s.course_id = :id AND s.status = 'PUBLISHED' AND l.status = 'PUBLISHED'
                    """, courseId);
            if (lessons == 0) {
                throw conflict("COURSE_CONTENT_REQUIRED", "A course requires at least one published lesson.");
            }
        }
        jdbcClient.sql("UPDATE course SET status = :status WHERE id = :id")
                .param("status", normalized)
                .param("id", courseId.toString())
                .update();
        return managedCourse(principal, courseId);
    }

    public CourseOutline getOutline(EduTwinPrincipal principal, UUID courseId, boolean teacherView) {
        if (teacherView) {
            requireTeacherCourse(principal, courseId);
        } else {
            requireStudentCourse(principal, courseId);
        }
        LmsCourse course = teacherView ? managedCourse(principal, courseId) : publishedCourse(courseId);
        String statusClause = teacherView ? "AND s.status <> 'ARCHIVED'" : "AND s.status = 'PUBLISHED'";
        List<SectionRow> sectionRows = jdbcClient.sql("""
                SELECT s.id, s.title, s.description, s.position, s.status
                FROM lms_section s
                WHERE s.course_id = :courseId %s
                ORDER BY s.position
                """.formatted(statusClause))
                .param("courseId", courseId.toString())
                .query((rs, row) -> new SectionRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getInt("position"),
                        rs.getString("status")))
                .list();
        Map<UUID, List<LmsLesson>> lessons = new LinkedHashMap<>();
        for (SectionRow section : sectionRows) {
            String lessonStatus = teacherView ? "AND l.status <> 'ARCHIVED'" : "AND l.status = 'PUBLISHED'";
            List<LmsLesson> rows = jdbcClient.sql("""
                    SELECT l.id, l.title, l.summary, l.body, l.resource_url, l.position, l.status,
                           EXISTS(SELECT 1 FROM lms_lesson_progress p WHERE p.lesson_id = l.id AND p.student_id = :studentId) completed
                    FROM lms_lesson l
                    WHERE l.section_id = :sectionId %s
                    ORDER BY l.position
                    """.formatted(lessonStatus))
                    .param("sectionId", section.id().toString())
                    .param("studentId", principal.userId().toString())
                    .query((rs, row) -> new LmsLesson(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("title"),
                            rs.getString("summary"),
                            rs.getString("body"),
                            rs.getString("resource_url"),
                            rs.getInt("position"),
                            rs.getString("status"),
                            rs.getBoolean("completed")))
                    .list();
            lessons.put(section.id(), rows);
        }
        List<LmsSection> sections = sectionRows.stream()
                .map(section -> new LmsSection(
                        section.id(), section.title(), section.description(), section.position(),
                        section.status(), lessons.getOrDefault(section.id(), List.of())))
                .toList();
        List<AssessmentSummary> assessments = listAssessmentsInternal(principal, courseId, teacherView);
        int totalLessons = sections.stream().mapToInt(section -> section.lessons().size()).sum();
        int completed = (int) sections.stream()
                .flatMap(section -> section.lessons().stream())
                .filter(LmsLesson::completed)
                .count();
        return new CourseOutline(course, sections, assessments, completed, totalLessons);
    }

    @Transactional
    public LmsSection createSection(
            EduTwinPrincipal principal, UUID courseId, SectionMutationRequest request) {
        requireTeacherCourse(principal, courseId);
        requireText(request == null ? null : request.title(), "Section title is required.");
        String status = requireContentStatus(request.status());
        int position = nextPosition("lms_section", "course_id", "position", courseId);
        UUID sectionId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO lms_section(id, course_id, title, description, position, status)
                VALUES (:id, :courseId, :title, :description, :position, :status)
                """)
                .param("id", sectionId.toString())
                .param("courseId", courseId.toString())
                .param("title", request.title().trim())
                .param("description", clean(request.description()))
                .param("position", position)
                .param("status", status)
                .update();
        insertContentHistory("SECTION", sectionId, courseId, null, status, "CONTENT_CREATED", principal.userId());
        return new LmsSection(sectionId, request.title().trim(), clean(request.description()), position, status, List.of());
    }

    @Transactional
    public LmsLesson createLesson(
            EduTwinPrincipal principal, UUID sectionId, LessonMutationRequest request) {
        UUID courseId = courseForSection(sectionId);
        requireTeacherCourse(principal, courseId);
        requireText(request == null ? null : request.title(), "Lesson title is required.");
        requireText(request.body(), "Lesson body is required.");
        String status = requireContentStatus(request.status());
        int position = nextPosition("lms_lesson", "section_id", "position", sectionId);
        UUID lessonId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO lms_lesson(id, section_id, title, summary, body, resource_url, position, status)
                VALUES (:id, :sectionId, :title, :summary, :body, :resourceUrl, :position, :status)
                """)
                .param("id", lessonId.toString())
                .param("sectionId", sectionId.toString())
                .param("title", request.title().trim())
                .param("summary", clean(request.summary()))
                .param("body", request.body().trim())
                .param("resourceUrl", blankToNull(request.resourceUrl()))
                .param("position", position)
                .param("status", status)
                .update();
        insertContentHistory("LESSON", lessonId, courseId, null, status, "CONTENT_CREATED", principal.userId());
        return new LmsLesson(
                lessonId, request.title().trim(), clean(request.summary()), request.body().trim(),
                blankToNull(request.resourceUrl()), position, status, false);
    }

    public RosterList roster(EduTwinPrincipal principal, UUID courseId) {
        requireTeacherCourse(principal, courseId);
        List<RosterStudent> items = jdbcClient.sql("""
                SELECT u.id, u.username, u.display_name, sp.student_number, sp.college,
                       sp.major, sp.cohort_year, sp.class_name,
                       COALESCE(e.status, 'AVAILABLE') enrollment_status
                FROM user_account u
                JOIN student_profile sp ON sp.user_id = u.id
                JOIN user_role ur ON ur.user_id = u.id AND ur.role_code = 'STUDENT'
                LEFT JOIN course_enrollment e ON e.student_id = u.id AND e.course_id = :courseId
                WHERE u.enabled = TRUE
                ORDER BY CASE WHEN e.status = 'ACTIVE' THEN 0 ELSE 1 END, u.display_name
                LIMIT 2500
                """)
                .param("courseId", courseId.toString())
                .query((rs, row) -> {
                    String enrollmentStatus = rs.getString("enrollment_status");
                    return new RosterStudent(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("username"),
                            rs.getString("display_name"),
                            rs.getString("student_number"), rs.getString("college"),
                            rs.getString("major"), rs.getInt("cohort_year"), rs.getString("class_name"),
                            "ACTIVE".equals(enrollmentStatus),
                            enrollmentStatus);
                })
                .list();
        int enrolled = (int) items.stream().filter(RosterStudent::enrolled).count();
        return new RosterList(items, items.size(), enrolled);
    }

    @Transactional
    public void updateEnrollment(
            EduTwinPrincipal principal, UUID courseId, UUID studentId, boolean enrolled, String reason) {
        requireCourseOwner(principal, courseId);
        boolean studentExists = jdbcClient.sql("""
                SELECT EXISTS(SELECT 1 FROM student_profile sp JOIN user_account u ON u.id = sp.user_id
                              WHERE sp.user_id = :studentId AND u.enabled = TRUE) found
                """)
                .param("studentId", studentId.toString())
                .query((rs, row) -> rs.getBoolean("found"))
                .single();
        if (!studentExists) {
            throw notFound("STUDENT_NOT_FOUND", "The requested student does not exist.");
        }
        String previous = jdbcClient.sql("SELECT status FROM course_enrollment WHERE course_id=:courseId AND student_id=:studentId")
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .query(String.class).optional().orElse(null);
        String target = enrolled ? "ACTIVE" : "WITHDRAWN";
        if (!enrolled && (reason == null || reason.isBlank())) {
            throw badRequest("ENROLLMENT_REASON_REQUIRED", "Removing a student requires a reason.");
        }
        if (target.equals(previous)) return;
        jdbcClient.sql("""
                INSERT INTO course_enrollment(course_id, student_id, status)
                VALUES (:courseId, :studentId, :status)
                ON DUPLICATE KEY UPDATE status = VALUES(status), enrolled_at = CURRENT_TIMESTAMP(6)
                """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("status", target)
                .update();
        String action = enrolled ? (previous == null ? "JOINED" : "RESTORED") : "WITHDRAWN";
        jdbcClient.sql("""
                INSERT INTO lms_enrollment_history(
                    id,course_id,student_id,previous_status,target_status,action_type,reason,actor_id)
                VALUES (:id,:courseId,:studentId,:previous,:target,:action,:reason,:actorId)
                """).param("id", UUID.randomUUID().toString()).param("courseId", courseId.toString())
                .param("studentId", studentId.toString()).param("previous", previous).param("target", target)
                .param("action", action).param("reason", reason == null || reason.isBlank() ? "TEACHER_ENROLLMENT" : reason.trim())
                .param("actorId", principal.userId().toString()).update();
        incrementTokenVersion(studentId);
        evictDashboardAfterCommit(courseId);
    }

    private void incrementTokenVersion(UUID userId) {
        jdbcClient.sql("""
                        UPDATE user_account SET token_version = token_version + 1
                        WHERE id = :userId
                        """)
                .param("userId", userId.toString())
                .update();
    }

    private void evictDashboardAfterCommit(UUID courseId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            teacherDashboardService.evict(courseId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                teacherDashboardService.evict(courseId);
            }
        });
    }

    public AssessmentList listAssessments(
            EduTwinPrincipal principal, UUID courseId, boolean teacherView) {
        if (teacherView) requireTeacherCourse(principal, courseId);
        else requireStudentCourse(principal, courseId);
        List<AssessmentSummary> items = listAssessmentsInternal(principal, courseId, teacherView);
        return new AssessmentList(items, items.size());
    }

    public KnowledgeSkillList knowledgeSkills(EduTwinPrincipal principal, UUID courseId) {
        requireTeacherCourse(principal, courseId);
        List<KnowledgeSkillSummary> items = jdbcClient.sql("""
                SELECT DISTINCT ks.id, ks.source_skill_key, ks.name, ks.content_origin
                FROM course_question cq
                JOIN question_skill qs ON qs.question_id = cq.question_id
                JOIN knowledge_skill ks ON ks.id = qs.skill_id
                WHERE cq.course_id = :courseId AND cq.active = TRUE
                ORDER BY ks.source_skill_key
                """)
                .param("courseId", courseId.toString())
                .query((rs, row) -> new KnowledgeSkillSummary(
                        UUID.fromString(rs.getString("id")), rs.getString("source_skill_key"),
                        rs.getString("name"), rs.getString("content_origin")))
                .list();
        return new KnowledgeSkillList(items, items.size());
    }

    @Transactional
    public AssessmentDetail createAssessment(
            EduTwinPrincipal principal, UUID courseId, AssessmentMutationRequest request) {
        requireTeacherCourse(principal, courseId);
        validateAssessment(request);
        UUID assessmentId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO lms_assessment(id, course_id, title, description, assessment_type, status, due_at)
                VALUES (:id, :courseId, :title, :description, :type, 'DRAFT', :dueAt)
                """)
                .param("id", assessmentId.toString())
                .param("courseId", courseId.toString())
                .param("title", request.title().trim())
                .param("description", clean(request.description()))
                .param("type", request.assessmentType())
                .param("dueAt", timestamp(request.dueAt()))
                .update();

        int position = 1;
        for (AssessmentQuestionRequest question : request.questions()) {
            createAssessmentQuestion(courseId, assessmentId, question, position++);
        }
        insertAssessmentHistory(assessmentId, null, "DRAFT", "ASSESSMENT_CREATED", principal.userId());
        return assessment(principal, assessmentId, true);
    }

    @Transactional
    public AssessmentDetail updateAssessment(
            EduTwinPrincipal principal, UUID assessmentId, AssessmentMutationRequest request) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        validateAssessment(request);
        AssessmentFact current = assessmentFact(assessmentId);
        if (!"DRAFT".equals(current.status()) || hasAnySubmission(assessmentId)) {
            throw conflict("ASSESSMENT_CONTENT_LOCKED", "Assessment questions are locked after publication or submission.");
        }
        List<UUID> sourceQuestionIds = questionFacts(assessmentId).stream()
                .map(QuestionFact::sourceQuestionId).filter(java.util.Objects::nonNull).toList();
        jdbcClient.sql("DELETE FROM lms_assessment_question_skill WHERE question_id IN (SELECT id FROM lms_assessment_question WHERE assessment_id=:id)")
                .param("id", assessmentId.toString()).update();
        jdbcClient.sql("DELETE FROM lms_assessment_question WHERE assessment_id=:id")
                .param("id", assessmentId.toString()).update();
        for (UUID sourceQuestionId : sourceQuestionIds) {
            jdbcClient.sql("DELETE FROM course_question WHERE course_id=:courseId AND question_id=:questionId")
                    .param("courseId", courseId.toString()).param("questionId", sourceQuestionId.toString()).update();
            jdbcClient.sql("DELETE FROM question_skill WHERE question_id=:questionId")
                    .param("questionId", sourceQuestionId.toString()).update();
            jdbcClient.sql("DELETE FROM question WHERE id=:questionId")
                    .param("questionId", sourceQuestionId.toString()).update();
        }
        jdbcClient.sql("""
                UPDATE lms_assessment SET title=:title,description=:description,
                    assessment_type=:type,due_at=:dueAt WHERE id=:id
                """).param("title", request.title().trim()).param("description", clean(request.description()))
                .param("type", request.assessmentType()).param("dueAt", timestamp(request.dueAt()))
                .param("id", assessmentId.toString()).update();
        int position = 1;
        for (AssessmentQuestionRequest question : request.questions()) {
            createAssessmentQuestion(courseId, assessmentId, question, position++);
        }
        return assessment(principal, assessmentId, true);
    }

    @Transactional
    public AssessmentDetail cloneAssessment(
            EduTwinPrincipal principal, UUID assessmentId, AssessmentCloneRequest request) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        AssessmentFact source = assessmentFact(assessmentId);
        String title = request == null || request.title() == null || request.title().isBlank()
                ? source.title() + " (Copy)" : request.title().trim();
        UUID cloneId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO lms_assessment(id,course_id,title,description,assessment_type,status,due_at)
                VALUES (:id,:courseId,:title,:description,:type,'DRAFT',:dueAt)
                """).param("id", cloneId.toString()).param("courseId", courseId.toString())
                .param("title", title).param("description", source.description()).param("type", source.type())
                .param("dueAt", timestamp(request == null ? null : request.dueAt())).update();
        for (QuestionFact question : questionFacts(assessmentId)) {
            UUID questionId = UUID.randomUUID();
            jdbcClient.sql("""
                    INSERT INTO lms_assessment_question(
                        id,assessment_id,source_question_id,prompt,options_json,correct_choice_id,points,position)
                    VALUES (:id,:assessmentId,:sourceQuestionId,:prompt,CAST(:options AS JSON),:correctChoiceId,:points,:position)
                    """).param("id", questionId.toString()).param("assessmentId", cloneId.toString())
                    .param("sourceQuestionId", question.sourceQuestionId() == null ? null : question.sourceQuestionId().toString())
                    .param("prompt", question.prompt()).param("options", writeJson(question.options()))
                    .param("correctChoiceId", question.correctChoiceId()).param("points", question.points())
                    .param("position", question.position()).update();
            jdbcClient.sql("""
                    INSERT INTO lms_assessment_question_skill(question_id,skill_id,ordinal)
                    SELECT :cloneQuestionId,skill_id,ordinal FROM lms_assessment_question_skill WHERE question_id=:sourceQuestionId
                    """).param("cloneQuestionId", questionId.toString()).param("sourceQuestionId", question.id().toString()).update();
        }
        insertAssessmentHistory(cloneId, null, "DRAFT", "CLONED_FROM:" + assessmentId, principal.userId());
        return assessment(principal, cloneId, true);
    }

    @Transactional
    public AssessmentDetail changeAssessmentStatus(
            EduTwinPrincipal principal, UUID assessmentId, LmsDtos.StatusChangeRequest request) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        String normalized = requireAssessmentStatus(request == null ? null : request.requestedStatus());
        AssessmentFact current = assessmentFact(assessmentId);
        if (request.expectedCurrentStatus() != null && !request.expectedCurrentStatus().equals(current.status())) {
            throw conflict("STATUS_CHANGED", "The assessment status changed before this request was applied.");
        }
        if (current.status().equals(normalized)) return assessment(principal, assessmentId, true);
        boolean hasSubmissions = hasAnySubmission(assessmentId);
        boolean valid = switch (current.status()) {
            case "DRAFT" -> Set.of("PUBLISHED", "ARCHIVED").contains(normalized);
            case "PUBLISHED" -> "CLOSED".equals(normalized)
                    || ("DRAFT".equals(normalized) && !hasSubmissions);
            case "CLOSED" -> "ARCHIVED".equals(normalized) || ("PUBLISHED".equals(normalized)
                    && current.dueAt() != null && current.dueAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
            case "CANCELLED" -> "ARCHIVED".equals(normalized);
            case "ARCHIVED" -> "DRAFT".equals(normalized) && !hasSubmissions;
            default -> false;
        };
        if (!valid) throw conflict("ASSESSMENT_STATUS_TRANSITION_INVALID", "The requested assessment status transition is not allowed.");
        if ("PUBLISHED".equals(normalized) && count(
                "SELECT COUNT(*) FROM lms_assessment_question WHERE assessment_id = :id", assessmentId) == 0) {
            throw conflict("ASSESSMENT_QUESTIONS_REQUIRED", "An assessment requires at least one question.");
        }
        jdbcClient.sql("""
                UPDATE lms_assessment SET status=:status,
                    published_at=CASE WHEN :status='PUBLISHED' THEN COALESCE(published_at,CURRENT_TIMESTAMP(6)) ELSE published_at END,
                    closed_at=CASE WHEN :status='CLOSED' THEN CURRENT_TIMESTAMP(6) WHEN :status='PUBLISHED' THEN NULL ELSE closed_at END
                WHERE id=:id
                """)
                .param("status", normalized)
                .param("id", assessmentId.toString())
                .update();
        String reason = request.reason() == null || request.reason().isBlank() ? "TEACHER_STATUS_CHANGE" : request.reason().trim();
        insertAssessmentHistory(assessmentId, current.status(), normalized, reason, principal.userId());
        if ("PUBLISHED".equals(normalized)) {
            notifyEnrolled(courseId, "ASSESSMENT_PUBLISHED", "assessment:" + assessmentId + ":published");
        }
        return assessment(principal, assessmentId, true);
    }

    public AssessmentDetail getAssessment(EduTwinPrincipal principal, UUID assessmentId) {
        UUID courseId = courseForAssessment(assessmentId);
        requireStudentCourse(principal, courseId);
        return assessment(principal, assessmentId, false);
    }

    @Transactional
    public void completeLesson(EduTwinPrincipal principal, UUID courseId, UUID lessonId) {
        requireStudentCourse(principal, courseId);
        boolean publishedLesson = jdbcClient.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM lms_lesson l JOIN lms_section s ON s.id = l.section_id
                    WHERE l.id = :lessonId AND s.course_id = :courseId
                      AND l.status = 'PUBLISHED' AND s.status = 'PUBLISHED') found
                """)
                .param("lessonId", lessonId.toString())
                .param("courseId", courseId.toString())
                .query((rs, row) -> rs.getBoolean("found"))
                .single();
        if (!publishedLesson) throw notFound("LESSON_NOT_FOUND", "The published lesson does not exist.");
        jdbcClient.sql("""
                INSERT INTO lms_lesson_progress(lesson_id, student_id)
                VALUES (:lessonId, :studentId)
                ON DUPLICATE KEY UPDATE completed_at = completed_at
                """)
                .param("lessonId", lessonId.toString())
                .param("studentId", principal.userId().toString())
                .update();
    }

    public AssessmentResult submitAssessment(
            EduTwinPrincipal principal,
            UUID assessmentId,
            String idempotencyKey,
            AssessmentSubmissionRequest request) {
        requireStudent(principal);
        UUID courseId = courseForAssessment(assessmentId);
        requireStudentCourse(principal, courseId);
        validateSubmission(request);
        String requestHash = requestHash(request);
        String keyHash = sha256(idempotencyKey);
        Optional<AssessmentResult> replay = replay(principal.userId(), keyHash, requestHash);
        if (replay.isPresent()) return replay.orElseThrow();

        AssessmentCreationResult created = transactionTemplate.execute(status -> createSubmission(
                principal, assessmentId, courseId, keyHash, requestHash, request));
        if (created == null) throw new IllegalStateException("The LMS submission transaction returned no result.");
        queueTwinUpdates(principal, courseId, idempotencyKey, created.linkedAnswers());
        return created.result();
    }

    protected AssessmentCreationResult createSubmission(
            EduTwinPrincipal principal,
            UUID assessmentId,
            UUID courseId,
            String keyHash,
            String requestHash,
            AssessmentSubmissionRequest request) {
        AssessmentFact assessment = assessmentFactForUpdate(assessmentId);
        if (Set.of("DRAFT", "CANCELLED", "ARCHIVED").contains(assessment.status())) {
            throw conflict("ASSESSMENT_NOT_PUBLISHED", "The assessment is not open for submission.");
        }
        int existingAttempts = jdbcClient.sql("""
                SELECT COUNT(*) FROM lms_submission WHERE assessment_id=:assessmentId AND student_id=:studentId
                """)
                .param("assessmentId", assessmentId.toString())
                .param("studentId", principal.userId().toString())
                .query(Integer.class).single();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean globallyOpen = "PUBLISHED".equals(assessment.status())
                && (assessment.dueAt() == null || assessment.dueAt().isAfter(now));
        Optional<AttemptGrant> grant = Optional.empty();
        if (existingAttempts > 0 || !globallyOpen) {
            grant = jdbcClient.sql("""
                    SELECT id,request_type,personal_due_at FROM lms_attempt_request
                    WHERE assessment_id=:assessmentId AND student_id=:studentId
                      AND status='APPROVED' AND consumed_at IS NULL
                      AND personal_due_at > CURRENT_TIMESTAMP(6)
                    ORDER BY decided_at,id LIMIT 1 FOR UPDATE
                    """).param("assessmentId", assessmentId.toString())
                    .param("studentId", principal.userId().toString())
                    .query((rs, row) -> new AttemptGrant(
                            UUID.fromString(rs.getString("id")), rs.getString("request_type"),
                            offsetDateTime(rs.getTimestamp("personal_due_at"))))
                    .optional();
            if (grant.isEmpty()) {
                throw conflict(existingAttempts > 0 ? "ASSESSMENT_ALREADY_SUBMITTED" : "ASSESSMENT_CLOSED",
                        existingAttempts > 0
                                ? "An approved additional attempt is required."
                                : "The assessment due time has passed.");
            }
        }

        List<QuestionFact> questions = questionFacts(assessmentId);
        Map<UUID, AssessmentAnswerRequest> submitted = new HashMap<>();
        for (AssessmentAnswerRequest answer : request.answers()) {
            if (answer == null || answer.questionId() == null || submitted.containsKey(answer.questionId())) {
                throw badRequest("ASSESSMENT_ANSWERS_INVALID", "Each assessment question requires one unique answer.");
            }
            submitted.put(answer.questionId(), answer);
        }
        if (submitted.size() != questions.size() || !submitted.keySet().equals(
                questions.stream().map(QuestionFact::id).collect(java.util.stream.Collectors.toSet()))) {
            throw badRequest("ASSESSMENT_ANSWERS_INCOMPLETE", "Every assessment question must be answered exactly once.");
        }

        BigDecimal maxScore = questions.stream().map(QuestionFact::points).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal score = BigDecimal.ZERO;
        List<AssessmentAnswerResult> answerResults = new ArrayList<>();
        List<LinkedAnswer> linkedAnswers = new ArrayList<>();
        UUID submissionId = UUID.randomUUID();
        for (QuestionFact question : questions) {
            AssessmentAnswerRequest answer = submitted.get(question.id());
            if (question.options().stream().noneMatch(option -> option.choiceId().equals(answer.selectedChoiceId()))) {
                throw badRequest("ASSESSMENT_CHOICE_INVALID", "A selected choice is not defined by the assessment.");
            }
            boolean correct = question.correctChoiceId().equals(answer.selectedChoiceId());
            BigDecimal awarded = correct ? question.points() : BigDecimal.ZERO;
            score = score.add(awarded);
            answerResults.add(new AssessmentAnswerResult(
                    question.id(), answer.selectedChoiceId(), question.correctChoiceId(), correct, awarded));
            if (question.sourceQuestionId() != null) {
                linkedAnswers.add(new LinkedAnswer(question.sourceQuestionId(), answer.selectedChoiceId()));
            }
        }
        OffsetDateTime submittedAt = OffsetDateTime.now(ZoneOffset.UTC);
        AttemptGrant selectedGrant = grant.orElse(null);
        int attemptNumber = existingAttempts + 1;
        String attemptType = selectedGrant == null ? "INITIAL" : selectedGrant.type();
        jdbcClient.sql("""
                INSERT INTO lms_submission(
                    id,assessment_id,student_id,attempt_number,attempt_type,grant_request_id,
                    idempotency_key_hash,request_sha256,score,max_score,valid_for_grade,submitted_at)
                VALUES (:id,:assessmentId,:studentId,:attemptNumber,:attemptType,:grantId,
                    :keyHash,:requestHash,:score,:maxScore,TRUE,:submittedAt)
                """)
                .param("id", submissionId.toString())
                .param("assessmentId", assessmentId.toString())
                .param("studentId", principal.userId().toString())
                .param("attemptNumber", attemptNumber)
                .param("attemptType", attemptType)
                .param("grantId", selectedGrant == null ? null : selectedGrant.id().toString())
                .param("keyHash", keyHash)
                .param("requestHash", requestHash)
                .param("score", score)
                .param("maxScore", maxScore)
                .param("submittedAt", Timestamp.from(submittedAt.toInstant()))
                .update();
        if (selectedGrant != null) {
            jdbcClient.sql("""
                    UPDATE lms_attempt_request SET consumed_at=:consumedAt
                    WHERE id=:id AND consumed_at IS NULL
                    """).param("consumedAt", Timestamp.from(submittedAt.toInstant()))
                    .param("id", selectedGrant.id().toString()).update();
        }
        for (AssessmentAnswerResult answer : answerResults) {
            jdbcClient.sql("""
                    INSERT INTO lms_submission_answer(submission_id, question_id, selected_choice_id, correct, points_awarded)
                    VALUES (:submissionId, :questionId, :choice, :correct, :points)
                    """)
                    .param("submissionId", submissionId.toString())
                    .param("questionId", answer.questionId().toString())
                    .param("choice", answer.selectedChoiceId())
                    .param("correct", answer.correct())
                    .param("points", answer.pointsAwarded())
                    .update();
        }
        AssessmentResult result = new AssessmentResult(
                submissionId, assessmentId, principal.userId(), score, maxScore,
                percentage(score, maxScore), submittedAt, attemptNumber, attemptType, true,
                List.copyOf(answerResults));
        return new AssessmentCreationResult(result, List.copyOf(linkedAnswers));
    }

    public GradeList assessmentGrades(EduTwinPrincipal principal, UUID assessmentId) {
        UUID courseId = courseForAssessment(assessmentId);
        requireTeacherCourse(principal, courseId);
        List<GradeItem> items = jdbcClient.sql("""
                SELECT a.id assessment_id, a.title, u.id student_id, u.display_name,
                        s.id submission_id, s.score, s.max_score, s.submitted_at,
                        (SELECT COUNT(*) FROM lms_submission sx WHERE sx.assessment_id=a.id AND sx.student_id=u.id) attempt_count,
                       (SELECT COALESCE(SUM(q.points), 0) FROM lms_assessment_question q WHERE q.assessment_id = a.id) possible_score
                FROM lms_assessment a
                JOIN course_enrollment e ON e.course_id = a.course_id AND e.status = 'ACTIVE'
                JOIN user_account u ON u.id = e.student_id
                LEFT JOIN lms_submission s ON s.id = (
                    SELECT sx.id FROM lms_submission sx
                    WHERE sx.assessment_id=a.id AND sx.student_id=u.id AND sx.valid_for_grade=TRUE
                      AND a.status <> 'CANCELLED'
                    ORDER BY sx.score DESC,sx.submitted_at DESC LIMIT 1)
                WHERE a.id = :assessmentId
                ORDER BY s.submitted_at IS NULL, u.display_name
                """)
                .param("assessmentId", assessmentId.toString())
                .query((rs, row) -> grade(rs))
                .list();
        return new GradeList(items, items.size());
    }

    public AssessmentResult ownAssessmentSubmission(EduTwinPrincipal principal, UUID assessmentId) {
        requireStudent(principal);
        UUID courseId = courseForAssessment(assessmentId);
        requireStudentCourse(principal, courseId);
        SubmissionFact submission = jdbcClient.sql("""
                SELECT id,assessment_id,student_id,request_sha256,score,max_score,submitted_at,
                       attempt_number,attempt_type,valid_for_grade
                FROM lms_submission
                WHERE assessment_id = :assessmentId AND student_id = :studentId
                  AND valid_for_grade=TRUE
                ORDER BY score DESC,submitted_at DESC LIMIT 1
                """)
                .param("assessmentId", assessmentId.toString())
                .param("studentId", principal.userId().toString())
                .query((rs, row) -> submissionFact(rs))
                .optional()
                .orElseThrow(() -> notFound("ASSESSMENT_SUBMISSION_NOT_FOUND",
                        "The current student has not submitted this assessment."));
        return resultForSubmission(submission);
    }

    public AssessmentAttemptList ownAssessmentAttempts(EduTwinPrincipal principal, UUID assessmentId) {
        requireStudent(principal);
        UUID courseId = courseForAssessment(assessmentId);
        requireStudentCourse(principal, courseId);
        List<SubmissionFact> facts = jdbcClient.sql("""
                SELECT id,assessment_id,student_id,request_sha256,score,max_score,submitted_at,
                       attempt_number,attempt_type,valid_for_grade
                FROM lms_submission
                WHERE assessment_id=:assessmentId AND student_id=:studentId
                ORDER BY attempt_number
                """).param("assessmentId", assessmentId.toString())
                .param("studentId", principal.userId().toString())
                .query((rs, row) -> submissionFact(rs)).list();
        List<AssessmentResult> items = facts.stream().map(this::resultForSubmission).toList();
        SubmissionFact best = facts.stream().filter(SubmissionFact::validForGrade)
                .max(Comparator.comparing(SubmissionFact::score).thenComparing(SubmissionFact::submittedAt))
                .orElse(null);
        return new AssessmentAttemptList(items, items.size(), best == null ? null : best.id(), best == null ? null : best.score());
    }

    public GradeList studentGrades(EduTwinPrincipal principal, UUID courseId) {
        requireStudentCourse(principal, courseId);
        List<GradeItem> items = jdbcClient.sql("""
                SELECT a.id assessment_id, a.title, u.id student_id, u.display_name,
                        s.id submission_id, s.score, s.max_score, s.submitted_at,
                        (SELECT COUNT(*) FROM lms_submission sx WHERE sx.assessment_id=a.id AND sx.student_id=u.id) attempt_count,
                       (SELECT COALESCE(SUM(q.points), 0) FROM lms_assessment_question q WHERE q.assessment_id = a.id) possible_score
                FROM lms_assessment a
                JOIN user_account u ON u.id = :studentId
                LEFT JOIN lms_submission s ON s.id = (
                    SELECT sx.id FROM lms_submission sx
                    WHERE sx.assessment_id=a.id AND sx.student_id=u.id AND sx.valid_for_grade=TRUE
                      AND a.status <> 'CANCELLED'
                    ORDER BY sx.score DESC,sx.submitted_at DESC LIMIT 1)
                WHERE a.course_id = :courseId AND a.status IN ('PUBLISHED','CLOSED','CANCELLED')
                ORDER BY a.due_at IS NULL, a.due_at, a.created_at
                """)
                .param("courseId", courseId.toString())
                .param("studentId", principal.userId().toString())
                .query((rs, row) -> grade(rs))
                .list();
        return new GradeList(items, items.size());
    }

    private void createAssessmentQuestion(
            UUID courseId,
            UUID assessmentId,
            AssessmentQuestionRequest question,
            int position) {
        validateQuestion(question);
        UUID lmsQuestionId = UUID.randomUUID();
        UUID sourceQuestionId = UUID.randomUUID();
        List<AssessmentOption> options = List.copyOf(question.options());
        String optionsJson = writeJson(options);
        List<UUID> skillIds = List.copyOf(question.skillIds());
        requireCourseSkills(courseId, skillIds);
        jdbcClient.sql("""
                INSERT INTO question(id, source_problem_key, prompt_text, answer_type, options_json,
                                     correct_answer, difficulty, data_version, active,
                                     knowledge_model_mode, content_origin)
                VALUES (:id, :sourceKey, :prompt, 'SINGLE_CHOICE', CAST(:options AS JSON),
                        :correctAnswer, 0.5, 'lms-authoring-v1', TRUE,
                        'ONLINE_BKT', 'TEACHER_AUTHORED')
                """)
                .param("id", sourceQuestionId.toString())
                .param("sourceKey", "LMS-" + sourceQuestionId)
                .param("prompt", question.prompt().trim())
                .param("options", optionsJson)
                .param("correctAnswer", question.correctChoiceId())
                .update();
        for (int index = 0; index < skillIds.size(); index++) {
            jdbcClient.sql("INSERT INTO question_skill(question_id, skill_id, ordinal) VALUES (:questionId, :skillId, :ordinal)")
                    .param("questionId", sourceQuestionId.toString())
                    .param("skillId", skillIds.get(index).toString())
                    .param("ordinal", index + 1)
                    .update();
        }
        int ordinal = nextPosition("course_question", "course_id", "ordinal", courseId);
        jdbcClient.sql("INSERT INTO course_question(course_id, question_id, active, ordinal) VALUES (:courseId, :questionId, TRUE, :ordinal)")
                .param("courseId", courseId.toString())
                .param("questionId", sourceQuestionId.toString())
                .param("ordinal", ordinal)
                .update();
        jdbcClient.sql("""
                INSERT INTO lms_assessment_question(
                    id, assessment_id, source_question_id, prompt, options_json, correct_choice_id, points, position)
                VALUES (:id, :assessmentId, :sourceQuestionId, :prompt, CAST(:options AS JSON), :correctChoiceId, :points, :position)
                """)
                .param("id", lmsQuestionId.toString())
                .param("assessmentId", assessmentId.toString())
                .param("sourceQuestionId", sourceQuestionId.toString())
                .param("prompt", question.prompt().trim())
                .param("options", optionsJson)
                .param("correctChoiceId", question.correctChoiceId())
                .param("points", question.points())
                .param("position", position)
                .update();
        for (int index = 0; index < skillIds.size(); index++) {
            jdbcClient.sql("""
                    INSERT INTO lms_assessment_question_skill(question_id, skill_id, ordinal)
                    VALUES (:questionId, :skillId, :ordinal)
                    """)
                    .param("questionId", lmsQuestionId.toString())
                    .param("skillId", skillIds.get(index).toString())
                    .param("ordinal", index + 1)
                    .update();
        }
    }

    private AssessmentDetail assessment(EduTwinPrincipal principal, UUID assessmentId, boolean teacherView) {
        AssessmentFact fact = assessmentFact(assessmentId);
        if (teacherView) requireTeacherCourse(principal, fact.courseId());
        else {
            requireStudentCourse(principal, fact.courseId());
            if (!Set.of("PUBLISHED", "CLOSED", "CANCELLED").contains(fact.status())) {
                throw notFound("ASSESSMENT_NOT_FOUND", "The published assessment does not exist.");
            }
        }
        List<AssessmentQuestion> questions = questionFacts(assessmentId).stream()
                .map(question -> new AssessmentQuestion(
                        question.id(), question.prompt(), question.options(), question.points(), question.position()))
                .toList();
        AssessmentSummary summary = summary(principal, fact, teacherView);
        return new AssessmentDetail(
                summary.assessmentId(), summary.courseId(), summary.title(), summary.description(),
                summary.assessmentType(), summary.status(), summary.dueAt(), summary.questionCount(),
                summary.submissionCount(), summary.submitted(), summary.score(), summary.maxScore(), questions);
    }

    private List<AssessmentSummary> listAssessmentsInternal(
            EduTwinPrincipal principal, UUID courseId, boolean teacherView) {
        String statusClause = teacherView
                ? "AND a.status <> 'ARCHIVED'"
                : "AND a.status IN ('PUBLISHED','CLOSED','CANCELLED')";
        List<AssessmentFact> facts = jdbcClient.sql("""
                SELECT a.id, a.course_id, a.title, a.description, a.assessment_type, a.status, a.due_at
                FROM lms_assessment a
                WHERE a.course_id = :courseId %s
                ORDER BY a.due_at IS NULL, a.due_at, a.created_at
                """.formatted(statusClause))
                .param("courseId", courseId.toString())
                .query((rs, row) -> new AssessmentFact(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("course_id")),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("assessment_type"),
                        rs.getString("status"),
                        offsetDateTime(rs.getTimestamp("due_at"))))
                .list();
        return facts.stream().map(fact -> summary(principal, fact, teacherView)).toList();
    }

    private AssessmentSummary summary(EduTwinPrincipal principal, AssessmentFact fact, boolean teacherView) {
        int questionCount = count(
                "SELECT COUNT(*) FROM lms_assessment_question WHERE assessment_id = :id", fact.id());
        int submissionCount = count("SELECT COUNT(*) FROM lms_submission WHERE assessment_id = :id", fact.id());
        BigDecimal maxScore = jdbcClient.sql("SELECT COALESCE(SUM(points), 0) value FROM lms_assessment_question WHERE assessment_id = :id")
                .param("id", fact.id().toString())
                .query((rs, row) -> rs.getBigDecimal("value"))
                .single();
        Optional<BigDecimal> score = teacherView || "CANCELLED".equals(fact.status()) ? Optional.empty() : jdbcClient.sql("""
                SELECT score FROM lms_submission
                WHERE assessment_id=:id AND student_id=:studentId AND valid_for_grade=TRUE
                ORDER BY score DESC,submitted_at DESC LIMIT 1
                """)
                .param("id", fact.id().toString())
                .param("studentId", principal.userId().toString())
                .query(BigDecimal.class)
                .optional();
        int attemptCount = teacherView ? 0 : countForStudent(
                "SELECT COUNT(*) FROM lms_submission WHERE assessment_id=:id AND student_id=:studentId",
                fact.id(), principal.userId());
        int remaining = teacherView ? 0 : countForStudent("""
                SELECT COUNT(*) FROM lms_attempt_request
                WHERE assessment_id=:id AND student_id=:studentId AND status='APPROVED'
                  AND consumed_at IS NULL AND personal_due_at > CURRENT_TIMESTAMP(6)
                """, fact.id(), principal.userId());
        boolean globalAvailable = "PUBLISHED".equals(fact.status())
                && (fact.dueAt() == null || fact.dueAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
        String learnerState = teacherView ? null
                : "CANCELLED".equals(fact.status()) ? "CANCELLED"
                : score.isPresent() ? "SUBMITTED"
                : (globalAvailable || remaining > 0) ? "AVAILABLE" : "CLOSED_UNSUBMITTED";
        return new AssessmentSummary(
                fact.id(), fact.courseId(), fact.title(), fact.description(), fact.type(), fact.status(),
                fact.dueAt(), questionCount, submissionCount, score.isPresent(), score.orElse(null), maxScore,
                learnerState, attemptCount, remaining);
    }

    private LmsCourse managedCourse(EduTwinPrincipal principal, UUID courseId) {
        requireTeacherCourse(principal, courseId);
        return jdbcClient.sql(courseSql(
                        "WHERE c.id = :courseId",
                        "(SELECT assignment_role FROM teaching_assignment WHERE course_id = c.id AND teacher_id = :teacherId)"))
                .param("courseId", courseId.toString())
                .param("teacherId", principal.userId().toString())
                .query((rs, row) -> course(rs))
                .optional()
                .orElseThrow(() -> notFound("COURSE_NOT_FOUND", "The requested course does not exist."));
    }

    private LmsCourse publishedCourse(UUID courseId) {
        return jdbcClient.sql(courseSql(
                        "WHERE c.id = :courseId AND c.status = 'PUBLISHED'", "'LEARNER'"))
                .param("courseId", courseId.toString())
                .query((rs, row) -> course(rs))
                .optional()
                .orElseThrow(() -> notFound("COURSE_NOT_FOUND", "The published course does not exist."));
    }

    private static String courseSql(String where, String assignmentRoleExpression) {
        return """
                SELECT c.id, c.code, c.title, c.term_label, c.description, c.status, c.starts_on,
                       c.college, c.department, c.credits,
                       (SELECT u.display_name FROM teaching_assignment tx JOIN user_account u ON u.id = tx.teacher_id WHERE tx.course_id = c.id LIMIT 1) instructor_name,
                       %s assignment_role,
                       (SELECT COUNT(*) FROM lms_section s WHERE s.course_id = c.id AND s.status <> 'ARCHIVED') section_count,
                       (SELECT COUNT(*) FROM lms_assessment a WHERE a.course_id = c.id AND a.status <> 'ARCHIVED') assessment_count,
                       (SELECT COUNT(*) FROM course_enrollment e WHERE e.course_id = c.id AND e.status = 'ACTIVE') enrolled_count
                FROM course c %s
                """.formatted(assignmentRoleExpression, where);
    }

    private static LmsCourse course(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LmsCourse(
                UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("title"),
                rs.getString("term_label"), rs.getString("description"), rs.getString("status"),
                rs.getDate("starts_on").toLocalDate(), rs.getInt("section_count"),
                rs.getInt("assessment_count"), rs.getInt("enrolled_count"),
                rs.getString("college"), rs.getString("department"), rs.getBigDecimal("credits"),
                rs.getString("instructor_name"), rs.getString("assignment_role"));
    }

    private AssessmentFact assessmentFact(UUID assessmentId) {
        return jdbcClient.sql("""
                SELECT id, course_id, title, description, assessment_type, status, due_at
                FROM lms_assessment WHERE id = :id
                """)
                .param("id", assessmentId.toString())
                .query((rs, row) -> new AssessmentFact(
                        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("course_id")),
                        rs.getString("title"), rs.getString("description"), rs.getString("assessment_type"),
                        rs.getString("status"), offsetDateTime(rs.getTimestamp("due_at"))))
                .optional()
                .orElseThrow(() -> notFound("ASSESSMENT_NOT_FOUND", "The requested assessment does not exist."));
    }

    private List<QuestionFact> questionFacts(UUID assessmentId) {
        return jdbcClient.sql("""
                SELECT id, source_question_id, prompt, options_json, correct_choice_id, points, position
                FROM lms_assessment_question WHERE assessment_id = :assessmentId ORDER BY position
                """)
                .param("assessmentId", assessmentId.toString())
                .query((rs, row) -> new QuestionFact(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("source_question_id") == null ? null : UUID.fromString(rs.getString("source_question_id")),
                        rs.getString("prompt"), parseOptions(rs.getString("options_json")),
                        rs.getString("correct_choice_id"), rs.getBigDecimal("points"), rs.getInt("position")))
                .list();
    }

    private Optional<AssessmentResult> replay(UUID studentId, String keyHash, String requestHash) {
        Optional<SubmissionFact> fact = jdbcClient.sql("""
                SELECT id,assessment_id,student_id,request_sha256,score,max_score,submitted_at,
                       attempt_number,attempt_type,valid_for_grade
                FROM lms_submission WHERE student_id = :studentId AND idempotency_key_hash = :keyHash
                """)
                .param("studentId", studentId.toString())
                .param("keyHash", keyHash)
                .query((rs, row) -> submissionFact(rs))
                .optional();
        if (fact.isEmpty()) return Optional.empty();
        SubmissionFact existing = fact.orElseThrow();
        if (!MessageDigest.isEqual(existing.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "The idempotency key is bound to another submission.");
        }
        return Optional.of(resultForSubmission(existing));
    }

    private AssessmentResult resultForSubmission(SubmissionFact submission) {
        List<AssessmentAnswerResult> answers = jdbcClient.sql("""
                SELECT a.question_id, a.selected_choice_id, q.correct_choice_id,
                       a.correct, a.points_awarded
                FROM lms_submission_answer a
                JOIN lms_assessment_question q ON q.id = a.question_id
                WHERE a.submission_id = :submissionId ORDER BY q.position
                """)
                .param("submissionId", submission.id().toString())
                .query((rs, row) -> new AssessmentAnswerResult(
                        UUID.fromString(rs.getString("question_id")), rs.getString("selected_choice_id"),
                        rs.getString("correct_choice_id"), rs.getBoolean("correct"),
                        rs.getBigDecimal("points_awarded")))
                .list();
        return new AssessmentResult(
                submission.id(), submission.assessmentId(), submission.studentId(), submission.score(),
                submission.maxScore(), percentage(submission.score(), submission.maxScore()),
                submission.submittedAt(), submission.attemptNumber(), submission.attemptType(),
                submission.validForGrade(), answers);
    }

    private void queueTwinUpdates(
            EduTwinPrincipal principal, UUID courseId, String idempotencyKey, List<LinkedAnswer> answers) {
        int index = 0;
        for (LinkedAnswer answer : answers) {
            try {
                answerSubmissionService.submit(
                        principal,
                        courseId,
                        idempotencyKey + ":lms:" + (++index),
                        new AnswerSubmission(answer.questionId(), answer.choiceId(), OffsetDateTime.now(ZoneOffset.UTC)),
                        UUID.randomUUID());
            } catch (RuntimeException exception) {
                LOGGER.warn("LMS assessment was scored but twin analysis could not be queued for question {}.",
                        answer.questionId(), exception);
            }
        }
    }

    private GradeItem grade(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal score = rs.getBigDecimal("score");
        BigDecimal maxScore = rs.getBigDecimal("max_score");
        if (maxScore == null) maxScore = rs.getBigDecimal("possible_score");
        boolean submitted = rs.getString("submission_id") != null;
        return new GradeItem(
                UUID.fromString(rs.getString("assessment_id")), rs.getString("title"),
                UUID.fromString(rs.getString("student_id")), rs.getString("display_name"), submitted,
                score, maxScore, submitted ? percentage(score, maxScore) : null,
                offsetDateTime(rs.getTimestamp("submitted_at")), rs.getInt("attempt_count"));
    }

    LmsCourse managedCourseView(EduTwinPrincipal principal, UUID courseId) {
        return managedCourse(principal, courseId);
    }

    AssessmentDetail teacherAssessmentView(EduTwinPrincipal principal, UUID assessmentId) {
        return assessment(principal, assessmentId, true);
    }

    private AssessmentFact assessmentFactForUpdate(UUID assessmentId) {
        return jdbcClient.sql("""
                SELECT id,course_id,title,description,assessment_type,status,due_at
                FROM lms_assessment WHERE id=:id FOR UPDATE
                """).param("id", assessmentId.toString())
                .query((rs, row) -> new AssessmentFact(
                        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("course_id")),
                        rs.getString("title"), rs.getString("description"), rs.getString("assessment_type"),
                        rs.getString("status"), offsetDateTime(rs.getTimestamp("due_at"))))
                .optional().orElseThrow(() -> notFound("ASSESSMENT_NOT_FOUND", "The requested assessment does not exist."));
    }

    private boolean hasAnySubmission(UUID assessmentId) {
        return count("SELECT COUNT(*) FROM lms_submission WHERE assessment_id=:id", assessmentId) > 0;
    }

    private int countForStudent(String sql, UUID assessmentId, UUID studentId) {
        return jdbcClient.sql(sql).param("id", assessmentId.toString())
                .param("studentId", studentId.toString()).query(Integer.class).single();
    }

    private void insertContentHistory(
            String type, UUID entityId, UUID courseId, String previous, String target, String reason, UUID actorId) {
        jdbcClient.sql("""
                INSERT INTO lms_content_status_history(
                    id,entity_type,entity_id,course_id,previous_status,target_status,reason,actor_id)
                VALUES (:id,:type,:entityId,:courseId,:previous,:target,:reason,:actorId)
                """).param("id", UUID.randomUUID().toString()).param("type", type)
                .param("entityId", entityId.toString()).param("courseId", courseId.toString())
                .param("previous", previous).param("target", target).param("reason", reason)
                .param("actorId", actorId.toString()).update();
    }

    private void insertAssessmentHistory(
            UUID assessmentId, String previous, String target, String reason, UUID actorId) {
        jdbcClient.sql("""
                INSERT INTO lms_assessment_status_history(
                    id,assessment_id,previous_status,target_status,reason,actor_id)
                VALUES (:id,:assessmentId,:previous,:target,:reason,:actorId)
                """).param("id", UUID.randomUUID().toString()).param("assessmentId", assessmentId.toString())
                .param("previous", previous).param("target", target).param("reason", reason)
                .param("actorId", actorId == null ? null : actorId.toString()).update();
    }

    private void notifyEnrolled(UUID courseId, String eventType, String dedupeKey) {
        jdbcClient.sql("SELECT student_id FROM course_enrollment WHERE course_id=:courseId AND status='ACTIVE'")
                .param("courseId", courseId.toString())
                .query((rs, row) -> UUID.fromString(rs.getString("student_id"))).list()
                .forEach(studentId -> notifications.publish(
                        studentId, eventType, dedupeKey + ":" + studentId, Map.of("courseId", courseId.toString())));
    }

    private UUID courseForSection(UUID sectionId) {
        return jdbcClient.sql("SELECT course_id FROM lms_section WHERE id = :id")
                .param("id", sectionId.toString())
                .query((rs, row) -> UUID.fromString(rs.getString("course_id")))
                .optional()
                .orElseThrow(() -> notFound("SECTION_NOT_FOUND", "The requested section does not exist."));
    }

    private UUID courseForAssessment(UUID assessmentId) {
        return jdbcClient.sql("SELECT course_id FROM lms_assessment WHERE id = :id")
                .param("id", assessmentId.toString())
                .query((rs, row) -> UUID.fromString(rs.getString("course_id")))
                .optional()
                .orElseThrow(() -> notFound("ASSESSMENT_NOT_FOUND", "The requested assessment does not exist."));
    }

    private void requireCourseSkills(UUID courseId, List<UUID> skillIds) {
        Set<UUID> requested = new HashSet<>(skillIds);
        List<UUID> available = knowledgeSkillsForCourse(courseId);
        if (requested.size() != skillIds.size() || !available.containsAll(requested)) {
            throw badRequest("ASSESSMENT_SKILLS_INVALID",
                    "Every selected knowledge skill must belong to the assessment course.");
        }
    }

    private List<UUID> knowledgeSkillsForCourse(UUID courseId) {
        return jdbcClient.sql("""
                SELECT DISTINCT qs.skill_id FROM course_question cq
                JOIN question_skill qs ON qs.question_id = cq.question_id
                WHERE cq.course_id = :courseId AND cq.active = TRUE
                ORDER BY qs.skill_id
                """)
                .param("courseId", courseId.toString())
                .query((rs, row) -> UUID.fromString(rs.getString("skill_id")))
                .list();
    }

    private boolean hasSubmission(UUID assessmentId, UUID studentId) {
        return jdbcClient.sql("""
                SELECT EXISTS(SELECT 1 FROM lms_submission
                    WHERE assessment_id = :assessmentId AND student_id = :studentId) found
                """)
                .param("assessmentId", assessmentId.toString())
                .param("studentId", studentId.toString())
                .query((rs, row) -> rs.getBoolean("found"))
                .single();
    }

    private static SubmissionFact submissionFact(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SubmissionFact(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("assessment_id")),
                UUID.fromString(rs.getString("student_id")), rs.getString("request_sha256"),
                rs.getBigDecimal("score"), rs.getBigDecimal("max_score"),
                offsetDateTime(rs.getTimestamp("submitted_at")), rs.getInt("attempt_number"),
                rs.getString("attempt_type"), rs.getBoolean("valid_for_grade"));
    }

    private void requireTeacherCourse(EduTwinPrincipal principal, UUID courseId) {
        requireTeacher(principal);
        boolean allowed = jdbcClient.sql("""
                SELECT EXISTS(SELECT 1 FROM teaching_assignment WHERE course_id = :courseId AND teacher_id = :teacherId) allowed
                """)
                .param("courseId", courseId.toString())
                .param("teacherId", principal.userId().toString())
                .query((rs, row) -> rs.getBoolean("allowed"))
                .single();
        if (!allowed) throw forbidden("COURSE_ACCESS_DENIED", "The teacher is not assigned to this course.");
    }

    private void requireCourseOwner(EduTwinPrincipal principal, UUID courseId) {
        requireTeacher(principal);
        boolean allowed = jdbcClient.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM teaching_assignment
                    WHERE course_id = :courseId AND teacher_id = :teacherId
                      AND assignment_role = 'OWNER'
                ) allowed
                """)
                .param("courseId", courseId.toString())
                .param("teacherId", principal.userId().toString())
                .query((rs, row) -> rs.getBoolean("allowed"))
                .single();
        if (!allowed) throw forbidden("COURSE_OWNER_REQUIRED", "Only the course owner may change course settings or enrollment.");
    }

    private void requireStudentCourse(EduTwinPrincipal principal, UUID courseId) {
        requireStudent(principal);
        boolean allowed = jdbcClient.sql("""
                SELECT EXISTS(SELECT 1 FROM course_enrollment e JOIN course c ON c.id = e.course_id
                              WHERE e.course_id = :courseId AND e.student_id = :studentId
                                AND e.status = 'ACTIVE' AND c.status = 'PUBLISHED') allowed
                """)
                .param("courseId", courseId.toString())
                .param("studentId", principal.userId().toString())
                .query((rs, row) -> rs.getBoolean("allowed"))
                .single();
        if (!allowed) throw forbidden("COURSE_ACCESS_DENIED", "An active enrollment in a published course is required.");
    }

    private static void requireTeacher(EduTwinPrincipal principal) {
        if (principal == null || !principal.enabled() || !"TEACHER".equals(principal.role().getValue())) {
            throw forbidden("TEACHER_ROLE_REQUIRED", "Only an enabled teacher may manage LMS content.");
        }
    }

    private static void requireStudent(EduTwinPrincipal principal) {
        if (principal == null || !principal.enabled() || !"STUDENT".equals(principal.role().getValue())) {
            throw forbidden("STUDENT_ROLE_REQUIRED", "Only an enabled student may use LMS learning endpoints.");
        }
    }

    private static void validateCourse(CourseMutationRequest request) {
        if (request == null || request.startsOn() == null) throw badRequest("COURSE_INVALID", "Course start date is required.");
        requireText(request.code(), "Course code is required.");
        requireText(request.title(), "Course title is required.");
        if (request.termLabel() != null && request.termLabel().trim().length() > 80) {
            throw badRequest("COURSE_INVALID", "Course term label must not exceed 80 characters.");
        }
        if (request.credits() == null
                || request.credits().compareTo(new BigDecimal("0.5")) < 0
                || request.credits().compareTo(new BigDecimal("20.0")) > 0) {
            throw badRequest("COURSE_INVALID", "Course credits must be between 0.5 and 20.0.");
        }
    }

    private static void validateAssessment(AssessmentMutationRequest request) {
        if (request == null || request.questions() == null || request.questions().isEmpty()) {
            throw badRequest("ASSESSMENT_INVALID", "At least one assessment question is required.");
        }
        requireText(request.title(), "Assessment title is required.");
        if (!ASSESSMENT_TYPES.contains(request.assessmentType())) {
            throw badRequest("ASSESSMENT_TYPE_INVALID", "Assessment type must be ASSIGNMENT or QUIZ.");
        }
        request.questions().forEach(LmsService::validateQuestion);
    }

    private static void validateQuestion(AssessmentQuestionRequest question) {
        if (question == null || question.options() == null || question.options().size() < 2
                || question.points() == null || question.points().signum() <= 0
                || question.skillIds() == null || question.skillIds().isEmpty()) {
            throw badRequest("ASSESSMENT_QUESTION_INVALID", "Each question requires at least two choices and positive points.");
        }
        if (question.skillIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(question.skillIds()).size() != question.skillIds().size()) {
            throw badRequest("ASSESSMENT_SKILLS_INVALID", "Knowledge skill identifiers must be unique and non-empty.");
        }
        requireText(question.prompt(), "Question prompt is required.");
        requireText(question.correctChoiceId(), "Correct choice is required.");
        Set<String> choiceIds = new HashSet<>();
        for (AssessmentOption option : question.options()) {
            if (option == null || option.choiceId() == null || option.choiceId().isBlank()
                    || option.label() == null || option.label().isBlank() || !choiceIds.add(option.choiceId())) {
                throw badRequest("ASSESSMENT_OPTIONS_INVALID", "Choice identifiers and labels must be unique and non-empty.");
            }
        }
        if (!choiceIds.contains(question.correctChoiceId())) {
            throw badRequest("ASSESSMENT_ANSWER_INVALID", "The correct choice must be one of the question options.");
        }
    }

    private static void validateSubmission(AssessmentSubmissionRequest request) {
        if (request == null || request.answers() == null || request.answers().isEmpty()) {
            throw badRequest("ASSESSMENT_SUBMISSION_INVALID", "Assessment answers are required.");
        }
        for (AssessmentAnswerRequest answer : request.answers()) {
            if (answer == null || answer.questionId() == null || answer.selectedChoiceId() == null
                    || answer.selectedChoiceId().isBlank()) {
                throw badRequest("ASSESSMENT_SUBMISSION_INVALID", "Each answer requires a question and selected choice.");
            }
        }
    }

    private int nextPosition(String table, String parentColumn, String orderColumn, UUID parentId) {
        if (!Set.of("lms_section", "lms_lesson", "course_question").contains(table)
                || !Set.of("course_id", "section_id").contains(parentColumn)
                || !Set.of("position", "ordinal").contains(orderColumn)) {
            throw new IllegalArgumentException("Unsupported ordering scope.");
        }
        return jdbcClient.sql("SELECT COALESCE(MAX(" + orderColumn + "), 0) + 1 next_position FROM " + table
                        + " WHERE " + parentColumn + " = :parentId")
                .param("parentId", parentId.toString())
                .query((rs, row) -> rs.getInt("next_position"))
                .single();
    }

    private int count(String sql, UUID id) {
        return jdbcClient.sql(sql).param("id", id.toString()).query(Integer.class).single();
    }

    private List<AssessmentOption> parseOptions(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted LMS options are invalid JSON.", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("LMS options could not be serialized.", exception);
        }
    }

    private String requestHash(AssessmentSubmissionRequest request) {
        List<AssessmentAnswerRequest> canonical = request.answers().stream()
                .sorted(Comparator.comparing(answer -> answer.questionId().toString()))
                .toList();
        return sha256(writeJson(canonical));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static BigDecimal percentage(BigDecimal score, BigDecimal maxScore) {
        if (score == null || maxScore == null || maxScore.signum() == 0) return BigDecimal.ZERO;
        return score.multiply(BigDecimal.valueOf(100)).divide(maxScore, 2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime offsetDateTime(Timestamp value) {
        return value == null ? null : OffsetDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static String requireContentStatus(String status) {
        if (status == null || !CONTENT_STATUSES.contains(status)) {
            throw badRequest("CONTENT_STATUS_INVALID", "Status must be DRAFT, PUBLISHED, or ARCHIVED.");
        }
        return status;
    }

    private static String requireAssessmentStatus(String status) {
        if (status == null || !ASSESSMENT_STATUSES.contains(status)) {
            throw badRequest("ASSESSMENT_STATUS_INVALID", "Assessment status is invalid.");
        }
        return status;
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

    private static DomainException badRequest(String code, String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, detail);
    }

    private static DomainException forbidden(String code, String detail) {
        return new DomainException(HttpStatus.FORBIDDEN, code, detail);
    }

    private static DomainException notFound(String code, String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, code, detail);
    }

    private static DomainException conflict(String code, String detail) {
        return new DomainException(HttpStatus.CONFLICT, code, detail);
    }

    private record SectionRow(UUID id, String title, String description, int position, String status) {}
    private record AssessmentFact(UUID id, UUID courseId, String title, String description, String type, String status, OffsetDateTime dueAt) {}
    private record QuestionFact(UUID id, UUID sourceQuestionId, String prompt, List<AssessmentOption> options, String correctChoiceId, BigDecimal points, int position) {}
    private record SubmissionFact(
            UUID id, UUID assessmentId, UUID studentId, String requestHash,
            BigDecimal score, BigDecimal maxScore, OffsetDateTime submittedAt,
            int attemptNumber, String attemptType, boolean validForGrade) {}
    private record AttemptGrant(UUID id, String type, OffsetDateTime personalDueAt) {}
    private record LinkedAnswer(UUID questionId, String choiceId) {}
    protected record AssessmentCreationResult(AssessmentResult result, List<LinkedAnswer> linkedAnswers) {}
}
