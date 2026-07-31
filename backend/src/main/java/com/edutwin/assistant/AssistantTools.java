package com.edutwin.assistant;

import com.edutwin.api.model.UserRole;
import com.edutwin.assistant.AssistantDtos.SourceRef;
import com.edutwin.identity.EduTwinPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AssistantTools {

    private static final int MAX_RESULT_CHARACTERS = 20_000;
    private static final int MAX_TOOL_CALLS = 6;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AssistantTools(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ToolSet create(EduTwinPrincipal principal, UUID messageId, ToolEventSink eventSink) {
        return create(principal, messageId, eventSink, () -> true);
    }

    public ToolSet create(
            EduTwinPrincipal principal,
            UUID messageId,
            ToolEventSink eventSink,
            BooleanSupplier messageWritable) {
        ToolContext context = new ToolContext(principal, messageId, eventSink, messageWritable);
        Object tools = switch (principal.role()) {
            case STUDENT -> new StudentTools(context);
            case TEACHER -> new TeacherTools(context);
            case COUNSELOR -> new CounselorTools(context);
            case ADMIN -> new AdminTools(context);
        };
        return new ToolSet(tools, context.sources);
    }

    public record ToolSet(Object tools, List<SourceRef> sources) {}

    public interface ToolEventSink {
        void started(int callIndex, UUID toolCallId, String toolName);
        void completed(int callIndex, UUID toolCallId, String toolName, boolean succeeded);
    }

    private final class ToolContext {
        private final EduTwinPrincipal principal;
        private final UUID messageId;
        private final ToolEventSink eventSink;
        private final BooleanSupplier messageWritable;
        private final AtomicInteger callCount = new AtomicInteger();
        private final List<SourceRef> sources = new ArrayList<>();

        private ToolContext(
                EduTwinPrincipal principal,
                UUID messageId,
                ToolEventSink eventSink,
                BooleanSupplier messageWritable) {
            this.principal = principal;
            this.messageId = messageId;
            this.eventSink = eventSink;
            this.messageWritable = messageWritable;
        }

        private String execute(
                String toolName,
                Object arguments,
                String sourceLabel,
                String deepLink,
                Supplier<Object> query) {
            int index = callCount.incrementAndGet();
            if (index > MAX_TOOL_CALLS) {
                throw new IllegalStateException("Assistant tool-call limit exceeded.");
            }
            UUID toolCallId = UUID.randomUUID();
            String requestJson = json(arguments);
            if (!messageWritable.getAsBoolean()) {
                throw new IllegalStateException("Assistant message is no longer writable.");
            }
            int inserted = jdbc.sql("""
                            INSERT INTO assistant_tool_call(
                                id, message_id, call_index, tool_name, request_sha256,
                                status, started_at)
                            SELECT :id, am.id, :callIndex, :toolName, :requestSha,
                                   'STARTED', CURRENT_TIMESTAMP(6)
                            FROM assistant_message am
                            JOIN assistant_conversation ac ON ac.id = am.conversation_id
                            WHERE am.id = :messageId AND am.status = 'PROCESSING'
                              AND ac.deleted_at IS NULL
                            """)
                    .param("id", toolCallId.toString())
                    .param("messageId", messageId.toString())
                    .param("callIndex", index)
                    .param("toolName", toolName)
                    .param("requestSha", sha256(requestJson))
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException("Assistant message is no longer writable.");
            }
            eventSink.started(index, toolCallId, toolName);
            try {
                String result = truncate(json(query.get()));
                if (!messageWritable.getAsBoolean()) {
                    throw new IllegalStateException("Assistant message is no longer writable.");
                }
                int updated = jdbc.sql("""
                                UPDATE assistant_tool_call atc
                                JOIN assistant_message am ON am.id = atc.message_id
                                JOIN assistant_conversation ac ON ac.id = am.conversation_id
                                SET atc.result_sha256 = :resultSha, atc.result_json = :result,
                                    atc.status = 'SUCCEEDED', atc.completed_at = CURRENT_TIMESTAMP(6)
                                WHERE atc.id = :id AND am.status = 'PROCESSING'
                                  AND ac.deleted_at IS NULL
                                """)
                        .param("resultSha", sha256(result))
                        .param("result", result)
                        .param("id", toolCallId.toString())
                        .update();
                if (updated != 1) {
                    throw new IllegalStateException("Assistant message is no longer writable.");
                }
                OffsetDateTime queriedAt = OffsetDateTime.now(ZoneOffset.UTC);
                if (sources.stream().noneMatch(source -> source.label().equals(sourceLabel)
                        && source.deepLink().equals(deepLink))) {
                    sources.add(new SourceRef(sourceLabel, queriedAt, deepLink));
                }
                eventSink.completed(index, toolCallId, toolName, true);
                return result;
            } catch (RuntimeException exception) {
                jdbc.sql("""
                                UPDATE assistant_tool_call
                                SET status = 'FAILED', error_code = 'TOOL_QUERY_FAILED',
                                    completed_at = CURRENT_TIMESTAMP(6)
                                WHERE id = :id
                                """)
                        .param("id", toolCallId.toString())
                        .update();
                eventSink.completed(index, toolCallId, toolName, false);
                throw exception;
            }
        }
    }

    public final class StudentTools {
        private final ToolContext context;

        private StudentTools(ToolContext context) {
            this.context = context;
        }

        @Tool(description = "List the authenticated student's active courses.")
        public String getMyCourses() {
            return context.execute("getMyCourses", Map.of(), "我的课程", "/student/courses", () -> rows("""
                    SELECT c.id course_id, c.code, c.title, c.status, c.term_label
                    FROM course c
                    JOIN course_enrollment ce ON ce.course_id = c.id
                    WHERE ce.student_id = :userId AND ce.status = 'ACTIVE'
                    ORDER BY c.code LIMIT 100
                    """, Map.of("userId", context.principal.userId().toString())));
        }

        @Tool(description = "List published assessments and the authenticated student's status for one course.")
        public String getMyTasks(String courseId) {
            UUID course = uuid(courseId, "courseId");
            return context.execute("getMyTasks", Map.of("courseId", courseId), "课程任务",
                    "/student/courses/" + course + "/assignments", () -> rows("""
                            SELECT a.id assessment_id, a.title, a.status, a.due_at,
                                   COUNT(DISTINCT s.id) attempt_count, MAX(s.score) best_score
                            FROM lms_assessment a
                            JOIN course_enrollment ce ON ce.course_id = a.course_id
                            LEFT JOIN lms_submission s ON s.assessment_id = a.id
                                AND s.student_id = ce.student_id
                            WHERE a.course_id = :courseId AND ce.student_id = :userId
                              AND ce.status = 'ACTIVE' AND a.status <> 'DRAFT'
                            GROUP BY a.id, a.title, a.status, a.due_at
                            ORDER BY a.due_at, a.title LIMIT 100
                            """, Map.of(
                                    "courseId", course.toString(),
                                    "userId", context.principal.userId().toString())));
        }

        @Tool(description = "Return the authenticated student's grades for one course.")
        public String getMyGrades(String courseId) {
            UUID course = uuid(courseId, "courseId");
            return context.execute("getMyGrades", Map.of("courseId", courseId), "学习成绩",
                    "/student/courses/" + course + "/grades", () -> rows("""
                            SELECT a.id assessment_id, a.title, MAX(s.score) best_score,
                                   MAX(s.max_score) max_score, MAX(s.submitted_at) last_submitted_at
                            FROM lms_assessment a
                            JOIN course_enrollment ce ON ce.course_id = a.course_id
                            LEFT JOIN lms_submission s ON s.assessment_id = a.id
                                AND s.student_id = ce.student_id
                            WHERE a.course_id = :courseId AND ce.student_id = :userId
                              AND ce.status = 'ACTIVE' AND a.status <> 'CANCELLED'
                            GROUP BY a.id, a.title ORDER BY a.title LIMIT 100
                            """, Map.of(
                                    "courseId", course.toString(),
                                    "userId", context.principal.userId().toString())));
        }

        @Tool(description = "Return completed and total lesson counts for one enrolled course.")
        public String getMyCourseProgress(String courseId) {
            UUID course = uuid(courseId, "courseId");
            return context.execute("getMyCourseProgress", Map.of("courseId", courseId), "课程进度",
                    "/student/courses/" + course + "/overview", () -> single("""
                            SELECT COUNT(DISTINCT lp.lesson_id) completed_lessons,
                                   COUNT(DISTINCT l.id) total_lessons
                            FROM course_enrollment ce
                            JOIN lms_section sec ON sec.course_id = ce.course_id AND sec.status = 'PUBLISHED'
                            JOIN lms_lesson l ON l.section_id = sec.id AND l.status = 'PUBLISHED'
                            LEFT JOIN lms_lesson_progress lp ON lp.lesson_id = l.id
                                AND lp.student_id = ce.student_id
                            WHERE ce.course_id = :courseId AND ce.student_id = :userId
                              AND ce.status = 'ACTIVE'
                            """, Map.of(
                                    "courseId", course.toString(),
                                    "userId", context.principal.userId().toString())));
        }

        @Tool(description = "Return the authenticated student's current mastery and course learning risk summary.")
        public String getMyLearningProfile(String courseId) {
            UUID course = activeStudentCourse(context, courseId);
            return context.execute("getMyLearningProfile", Map.of("courseId", courseId), "学情画像",
                    "/student/courses/" + course + "/twin", () -> single("""
                            SELECT ts.snapshot_version, ts.created_at evaluated_at,
                                   rp.risk_band, rp.calibrated_probability risk_probability,
                                   (SELECT AVG(tss.mastery_probability)
                                    FROM twin_snapshot_skill tss WHERE tss.snapshot_id = ts.id) average_mastery
                            FROM twin_current_pointer tcp
                            JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                            LEFT JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                            WHERE tcp.course_id = :courseId AND tcp.student_id = :userId
                            """, Map.of(
                                    "courseId", course.toString(),
                                    "userId", context.principal.userId().toString())));
        }

        @Tool(description = "Return the authenticated student's current learning plan and tasks.")
        public String getMyLearningPlan(String courseId) {
            UUID course = activeStudentCourse(context, courseId);
            return context.execute("getMyLearningPlan", Map.of("courseId", courseId), "学习计划",
                    "/student/courses/" + course + "/plan", () -> rows("""
                            SELECT lp.id plan_id, lp.status plan_status, lp.valid_until,
                                   lpi.id task_id, lpi.title, lpi.status task_status,
                                   lpi.due_at, q.prompt_text question_name, ks.name skill_name
                            FROM learning_plan_current_pointer lpcp
                            JOIN learning_plan lp ON lp.id = lpcp.learning_plan_id
                            JOIN learning_plan_item lpi ON lpi.learning_plan_id = lp.id
                            JOIN question q ON q.id = lpi.question_id
                            JOIN knowledge_skill ks ON ks.id = lpi.skill_id
                            WHERE lpcp.course_id = :courseId AND lpcp.student_id = :userId
                            ORDER BY lpi.ordinal LIMIT 100
                            """, Map.of(
                                    "courseId", course.toString(),
                                    "userId", context.principal.userId().toString())));
        }

        @Tool(description = "Return the authenticated student's recent in-app notifications.")
        public String getMyNotifications() {
            return context.execute("getMyNotifications", Map.of(), "通知中心", "/notifications", () -> rows("""
                    SELECT id notification_id, notification_type, title, deep_link, read_at, created_at
                    FROM user_notification
                    WHERE recipient_user_id = :userId
                    ORDER BY created_at DESC LIMIT 50
                    """, Map.of("userId", context.principal.userId().toString())));
        }
    }

    public final class TeacherTools {
        private final ToolContext context;

        private TeacherTools(ToolContext context) {
            this.context = context;
        }

        @Tool(description = "List courses assigned to the authenticated teacher.")
        public String getMyTeachingCourses() {
            return context.execute("getMyTeachingCourses", Map.of(), "教学课程", "/teacher/courses", () -> rows("""
                    SELECT c.id course_id, c.code, c.title, c.status, c.term_label
                    FROM course c JOIN teaching_assignment ta ON ta.course_id = c.id
                    WHERE ta.teacher_id = :userId ORDER BY c.code LIMIT 100
                    """, Map.of("userId", context.principal.userId().toString())));
        }

        @Tool(description = "Return aggregate teaching statistics for one assigned course.")
        public String getTeachingOverview(String courseId) {
            UUID course = teacherCourse(context, courseId);
            return context.execute("getTeachingOverview", Map.of("courseId", courseId), "教学驾驶舱",
                    "/teacher/courses/" + course + "/dashboard", () -> single("""
                            SELECT COUNT(DISTINCT ce.student_id) students,
                                   COUNT(DISTINCT a.id) assessments,
                                   COUNT(DISTINCT s.id) submissions,
                                   AVG(s.score / NULLIF(s.max_score, 0)) average_score
                            FROM course c
                            LEFT JOIN course_enrollment ce ON ce.course_id = c.id AND ce.status = 'ACTIVE'
                            LEFT JOIN lms_assessment a ON a.course_id = c.id
                            LEFT JOIN lms_submission s ON s.assessment_id = a.id
                            WHERE c.id = :courseId
                            """, Map.of("courseId", course.toString())));
        }

        @Tool(description = "Return section and lesson status counts for one assigned course.")
        public String getContentSummary(String courseId) {
            UUID course = teacherCourse(context, courseId);
            return context.execute("getContentSummary", Map.of("courseId", courseId), "课程内容",
                    "/teacher/courses/" + course + "/content", () -> rows("""
                            SELECT sec.status section_status, l.status lesson_status, COUNT(*) item_count
                            FROM lms_section sec JOIN lms_lesson l ON l.section_id = sec.id
                            WHERE sec.course_id = :courseId
                            GROUP BY sec.status, l.status ORDER BY sec.status, l.status
                            """, Map.of("courseId", course.toString())));
        }

        @Tool(description = "Return assessment submission and score aggregates for one assigned course.")
        public String getAssessmentStatistics(String courseId) {
            UUID course = teacherCourse(context, courseId);
            return context.execute("getAssessmentStatistics", Map.of("courseId", courseId), "考核管理",
                    "/teacher/courses/" + course + "/assessments", () -> rows("""
                            SELECT a.id assessment_id, a.title, a.status, a.due_at,
                                   COUNT(DISTINCT s.student_id) submitted_students,
                                   AVG(s.score / NULLIF(s.max_score, 0)) average_score
                            FROM lms_assessment a
                            LEFT JOIN lms_submission s ON s.assessment_id = a.id
                            WHERE a.course_id = :courseId
                            GROUP BY a.id, a.title, a.status, a.due_at
                            ORDER BY a.created_at DESC LIMIT 100
                            """, Map.of("courseId", course.toString())));
        }

        @Tool(description = "Return a roster summary for one assigned course without raw answers.")
        public String getRosterSummary(String courseId) {
            UUID course = teacherCourse(context, courseId);
            return context.execute("getRosterSummary", Map.of("courseId", courseId), "课程名单",
                    "/teacher/courses/" + course + "/roster", () -> rows("""
                            SELECT ce.status, COUNT(*) student_count
                            FROM course_enrollment ce WHERE ce.course_id = :courseId
                            GROUP BY ce.status ORDER BY ce.status
                            """, Map.of("courseId", course.toString())));
        }

        @Tool(description = "Return an authorized student's current learning profile for an assigned course.")
        public String getStudentLearningProfile(String courseId, String studentId) {
            UUID course = teacherCourse(context, courseId);
            UUID student = uuid(studentId, "studentId");
            return context.execute("getStudentLearningProfile",
                    Map.of("courseId", courseId, "studentId", studentId), "学生学情画像",
                    "/teacher/courses/" + course + "/students/" + student + "/twin", () -> single("""
                            SELECT u.display_name, sp.student_number, ts.snapshot_version,
                                   rp.risk_band, rp.calibrated_probability risk_probability,
                                   ts.created_at evaluated_at
                            FROM course_enrollment ce
                            JOIN user_account u ON u.id = ce.student_id
                            JOIN student_profile sp ON sp.user_id = ce.student_id
                            JOIN twin_current_pointer tcp ON tcp.course_id = ce.course_id
                                AND tcp.student_id = ce.student_id
                            JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                            LEFT JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                            WHERE ce.course_id = :courseId AND ce.student_id = :studentId
                              AND ce.status = 'ACTIVE'
                            """, Map.of("courseId", course.toString(), "studentId", student.toString())));
        }

        @Tool(description = "Return open course-learning-risk intervention cases for an assigned course.")
        public String getRiskCases(String courseId) {
            UUID course = teacherCourse(context, courseId);
            return context.execute("getRiskCases", Map.of("courseId", courseId), "风险干预",
                    "/teacher/risk-cases", () -> rows("""
                            SELECT rc.id case_id, u.display_name, sp.student_number,
                                   rc.risk_band, rc.status, rc.priority, rc.updated_at
                            FROM risk_case rc
                            JOIN user_account u ON u.id = rc.student_id
                            JOIN student_profile sp ON sp.user_id = rc.student_id
                            WHERE rc.course_id = :courseId
                            ORDER BY FIELD(rc.status, 'OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'),
                                     rc.updated_at DESC LIMIT 100
                            """, Map.of("courseId", course.toString())));
        }
    }

    public final class CounselorTools {
        private final ToolContext context;

        private CounselorTools(ToolContext context) {
            this.context = context;
        }

        @Tool(description = "List students within the authenticated counselor's assigned scope.")
        public String getAuthorizedStudents() {
            return context.execute("getAuthorizedStudents", Map.of(), "学生列表", "/counselor/students", () -> rows("""
                    SELECT DISTINCT u.id student_id, u.display_name, sp.student_number,
                           sp.major, sp.cohort_year, sp.class_name
                    FROM counselor_scope cs
                    JOIN student_profile sp ON sp.cohort_year = cs.cohort_year
                        AND (cs.class_name IS NULL OR cs.class_name = sp.class_name)
                    JOIN user_account u ON u.id = sp.user_id
                    WHERE cs.counselor_id = :userId AND u.enabled = TRUE
                    ORDER BY sp.class_name, sp.student_number LIMIT 100
                    """, Map.of("userId", context.principal.userId().toString())));
        }

        @Tool(description = "Return cross-course aggregate details for one authorized student without raw answers.")
        public String getStudentCrossCourseDetails(String studentId) {
            UUID student = counselorStudent(context, studentId);
            return context.execute("getStudentCrossCourseDetails", Map.of("studentId", studentId), "学生详情",
                    "/counselor/students/" + student, () -> rows("""
                            SELECT c.code, c.title,
                                   COUNT(DISTINCT lp.lesson_id) completed_lessons,
                                   COUNT(DISTINCT l.id) total_lessons,
                                   AVG(s.score / NULLIF(s.max_score, 0)) average_score,
                                   rp.risk_band
                            FROM course_enrollment ce
                            JOIN course c ON c.id = ce.course_id
                            LEFT JOIN lms_section sec ON sec.course_id = c.id AND sec.status = 'PUBLISHED'
                            LEFT JOIN lms_lesson l ON l.section_id = sec.id AND l.status = 'PUBLISHED'
                            LEFT JOIN lms_lesson_progress lp ON lp.lesson_id = l.id AND lp.student_id = ce.student_id
                            LEFT JOIN lms_assessment a ON a.course_id = c.id
                            LEFT JOIN lms_submission s ON s.assessment_id = a.id AND s.student_id = ce.student_id
                            LEFT JOIN twin_current_pointer tcp ON tcp.course_id = ce.course_id
                                AND tcp.student_id = ce.student_id
                            LEFT JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                            LEFT JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                            WHERE ce.student_id = :studentId
                            GROUP BY c.id, c.code, c.title, rp.risk_band ORDER BY c.code LIMIT 100
                            """, Map.of("studentId", student.toString())));
        }

        @Tool(description = "Compare up to four assigned classes by progress, submissions, scores, mastery and risk.")
        public String compareClasses(String classNames) {
            List<String> names = java.util.Arrays.stream(classNames.split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).distinct().limit(4).toList();
            if (names.isEmpty()) {
                throw new IllegalArgumentException("classNames must contain at least one class.");
            }
            return context.execute("compareClasses", Map.of("classNames", names), "班级对比",
                    "/counselor/classes/compare", () -> names.stream().map(name -> single("""
                            SELECT sp.class_name, COUNT(DISTINCT sp.user_id) students,
                                   AVG(CASE WHEN l.id IS NULL THEN NULL ELSE lp.lesson_id IS NOT NULL END) progress_rate,
                                   COUNT(DISTINCT CASE WHEN s.id IS NOT NULL
                                       THEN CONCAT(a.id, ':', sp.user_id) END)
                                       / NULLIF(COUNT(DISTINCT CONCAT(a.id, ':', sp.user_id)), 0) submission_rate,
                                   AVG(s.score / NULLIF(s.max_score, 0)) average_score,
                                   AVG(tss.mastery_probability) average_mastery,
                                   SUM(rp.risk_band = 'HIGH') high_risk_count
                            FROM counselor_scope cs
                            JOIN student_profile sp ON sp.cohort_year = cs.cohort_year
                                AND (cs.class_name IS NULL OR cs.class_name = sp.class_name)
                            LEFT JOIN course_enrollment ce ON ce.student_id = sp.user_id AND ce.status = 'ACTIVE'
                            LEFT JOIN lms_section sec ON sec.course_id = ce.course_id AND sec.status = 'PUBLISHED'
                            LEFT JOIN lms_lesson l ON l.section_id = sec.id AND l.status = 'PUBLISHED'
                            LEFT JOIN lms_lesson_progress lp ON lp.lesson_id = l.id AND lp.student_id = sp.user_id
                            LEFT JOIN lms_assessment a ON a.course_id = ce.course_id
                            LEFT JOIN lms_submission s ON s.assessment_id = a.id AND s.student_id = sp.user_id
                            LEFT JOIN twin_current_pointer tcp ON tcp.course_id = ce.course_id AND tcp.student_id = sp.user_id
                            LEFT JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                            LEFT JOIN twin_snapshot_skill tss ON tss.snapshot_id = tcp.snapshot_id
                            LEFT JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                            WHERE cs.counselor_id = :userId AND sp.class_name = :className
                            GROUP BY sp.class_name
                            """, Map.of("userId", context.principal.userId().toString(), "className", name))).toList());
        }

        @Tool(description = "Return intervention cases within the authenticated counselor's scope.")
        public String getCounselorRiskCases() {
            return context.execute("getCounselorRiskCases", Map.of(), "风险工单", "/counselor/risk-cases", () -> rows("""
                    SELECT DISTINCT rc.id case_id, u.display_name, sp.student_number, sp.class_name,
                           c.code course_code, rc.risk_band, rc.status, rc.priority, rc.updated_at
                    FROM risk_case rc
                    JOIN student_profile sp ON sp.user_id = rc.student_id
                    JOIN user_account u ON u.id = rc.student_id
                    JOIN course c ON c.id = rc.course_id
                    JOIN counselor_scope cs ON cs.cohort_year = sp.cohort_year
                        AND (cs.class_name IS NULL OR cs.class_name = sp.class_name)
                    WHERE cs.counselor_id = :userId
                    ORDER BY rc.updated_at DESC LIMIT 100
                    """, Map.of("userId", context.principal.userId().toString())));
        }
    }

    public final class AdminTools {
        private final ToolContext context;

        private AdminTools(ToolContext context) {
            this.context = context;
        }

        @Tool(description = "Return aggregate system governance counts without learning content.")
        public String getSystemOverview() {
            return context.execute("getSystemOverview", Map.of(), "系统概览", "/admin/overview", () -> Map.of(
                    "enabledUsers", scalar("SELECT COUNT(*) FROM user_account WHERE enabled = TRUE", Map.of()),
                    "organizations", scalar("SELECT COUNT(*) FROM organization_unit WHERE enabled = TRUE", Map.of()),
                    "enabledDatasets", scalar("SELECT COUNT(*) FROM dataset_version WHERE lifecycle_status = 'ENABLED'", Map.of()),
                    "modelDeployments", scalar("SELECT COUNT(*) FROM model_deployment", Map.of())));
        }

        @Tool(description = "Search account metadata with usernames masked and no learning content.")
        public String searchUsers(String query) {
            String value = query == null ? "" : query.trim();
            return context.execute("searchUsers", Map.of("query", value), "账号管理", "/admin/users", () -> rows("""
                    SELECT u.id user_id,
                           CONCAT(LEFT(u.username, 2), '***', RIGHT(u.username, 2)) masked_username,
                           u.display_name, u.enabled, GROUP_CONCAT(ur.role_code ORDER BY ur.role_code) roles
                    FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                    WHERE (:query = '' OR u.username LIKE CONCAT('%', :query, '%')
                           OR u.display_name LIKE CONCAT('%', :query, '%'))
                    GROUP BY u.id, u.username, u.display_name, u.enabled
                    ORDER BY u.display_name LIMIT 50
                    """, Map.of("query", value)));
        }

        @Tool(description = "Return enabled organization and academic-term summaries.")
        public String getOrganizationSummary() {
            return context.execute("getOrganizationSummary", Map.of(), "组织管理", "/admin/organizations", () -> rows("""
                    SELECT unit_type, COUNT(*) total, SUM(enabled) enabled_count
                    FROM organization_unit GROUP BY unit_type ORDER BY unit_type
                    """, Map.of()));
        }

        @Tool(description = "Return dataset lifecycle states and reference impact without manifests or hashes.")
        public String getDatasetImpact() {
            return context.execute("getDatasetImpact", Map.of(), "数据版本", "/admin/model-data", () -> rows("""
                    SELECT dv.version_id, ds.name source_name, dv.lifecycle_status,
                           EXISTS(SELECT 1 FROM course c WHERE c.data_version = dv.version_id) course_referenced,
                           EXISTS(SELECT 1 FROM model_version mv WHERE mv.dataset_version_id = dv.version_id
                                  AND mv.status IN ('ACTIVE', 'FROZEN', 'ROLLED_BACK')) model_referenced
                    FROM dataset_version dv JOIN dataset_source ds ON ds.id = dv.source_id
                    ORDER BY dv.processed_at DESC LIMIT 100
                    """, Map.of()));
        }

        @Tool(description = "Return active and rollback model deployment pointers by task.")
        public String getModelDeployments() {
            return context.execute("getModelDeployments", Map.of(), "模型部署", "/admin/model-data", () -> rows("""
                    SELECT task_name, active_version_id, rollback_version_id, deployed_at, deployed_by
                    FROM model_deployment ORDER BY task_name
                    """, Map.of()));
        }

        @Tool(description = "Search redacted audit metadata without passwords, chat bodies or raw answers.")
        public String searchAuditEvents(String action) {
            String value = action == null ? "" : action.trim();
            return context.execute("searchAuditEvents", Map.of("action", value), "审计日志", "/admin/audit", () -> rows("""
                    SELECT id audit_id, actor_user_id, active_role, action, target_type,
                           target_id, outcome, error_code, correlation_id, created_at
                    FROM admin_audit_event
                    WHERE (:action = '' OR action = :action)
                    ORDER BY created_at DESC LIMIT 100
                    """, Map.of("action", value)));
        }
    }

    private UUID teacherCourse(ToolContext context, String courseId) {
        UUID course = uuid(courseId, "courseId");
        long allowed = scalar("""
                SELECT COUNT(*) FROM teaching_assignment
                WHERE teacher_id = :userId AND course_id = :courseId
                """, Map.of(
                        "userId", context.principal.userId().toString(),
                        "courseId", course.toString()));
        if (allowed != 1) {
            throw new IllegalArgumentException("The course is outside the teacher scope.");
        }
        return course;
    }

    private UUID activeStudentCourse(ToolContext context, String courseId) {
        UUID course = uuid(courseId, "courseId");
        long allowed = scalar("""
                SELECT COUNT(*) FROM course_enrollment
                WHERE student_id = :userId AND course_id = :courseId AND status = 'ACTIVE'
                """, Map.of(
                        "userId", context.principal.userId().toString(),
                        "courseId", course.toString()));
        if (allowed != 1) {
            throw new IllegalArgumentException("The course is outside the active student enrollment scope.");
        }
        return course;
    }

    private UUID counselorStudent(ToolContext context, String studentId) {
        UUID student = uuid(studentId, "studentId");
        long allowed = scalar("""
                SELECT COUNT(DISTINCT sp.user_id)
                FROM counselor_scope cs JOIN student_profile sp
                  ON sp.cohort_year = cs.cohort_year
                 AND (cs.class_name IS NULL OR cs.class_name = sp.class_name)
                WHERE cs.counselor_id = :userId AND sp.user_id = :studentId
                """, Map.of(
                        "userId", context.principal.userId().toString(),
                        "studentId", student.toString()));
        if (allowed != 1) {
            throw new IllegalArgumentException("The student is outside the counselor scope.");
        }
        return student;
    }

    private List<Map<String, Object>> rows(String sql, Map<String, ?> parameters) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Map.Entry<String, ?> parameter : parameters.entrySet()) {
            statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query((resultSet, rowNumber) -> {
            ResultSetMetaData metadata = resultSet.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
            }
            return row;
        }).list();
    }

    private Map<String, Object> single(String sql, Map<String, ?> parameters) {
        List<Map<String, Object>> rows = rows(sql, parameters);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private long scalar(String sql, Map<String, ?> parameters) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Map.Entry<String, ?> parameter : parameters.entrySet()) {
            statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(Long.class).single();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Assistant tool JSON serialization failed.", exception);
        }
    }

    private static String truncate(String value) {
        return value.length() <= MAX_RESULT_CHARACTERS
                ? value
                : value.substring(0, MAX_RESULT_CHARACTERS);
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be a UUID.", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
