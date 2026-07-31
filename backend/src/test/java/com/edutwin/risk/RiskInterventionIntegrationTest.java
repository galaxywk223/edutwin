package com.edutwin.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class RiskInterventionIntegrationTest {
    private static final UUID TEACHER_ID = uuid("a1000000-0000-0000-0000-000000000001");
    private static final UUID STUDENT_ID = uuid("a1000000-0000-0000-0000-000000000002");
    private static final UUID OUTSIDE_TEACHER_ID = uuid("a1000000-0000-0000-0000-000000000003");
    private static final UUID COURSE_ID = uuid("a1000000-0000-0000-0000-000000000010");
    private static final UUID PREDICTION_ID = uuid("a1000000-0000-0000-0000-000000000020");

    @Autowired private JdbcClient jdbc;
    @Autowired private RiskInterventionService service;

    @BeforeEach
    void seedCurrentMediumRisk() {
        jdbc.sql("""
                INSERT INTO role_definition(code, description) VALUES
                    ('TEACHER', 'Teacher'), ('STUDENT', 'Student')
                ON DUPLICATE KEY UPDATE description = VALUES(description)
                """).update();
        account(TEACHER_ID, "risk-teacher", "Risk Teacher");
        account(OUTSIDE_TEACHER_ID, "outside-risk-teacher", "Outside Teacher");
        account(STUDENT_ID, "risk-student", "Risk Student");
        jdbc.sql("""
                        INSERT INTO user_role(user_id, role_code) VALUES
                            (:teacher, 'TEACHER'), (:outside, 'TEACHER'), (:student, 'STUDENT')
                        """).param("teacher", TEACHER_ID.toString())
                .param("outside", OUTSIDE_TEACHER_ID.toString()).param("student", STUDENT_ID.toString()).update();
        jdbc.sql("INSERT INTO teacher_profile(user_id, staff_key) VALUES (:id, 'risk-teacher-key')")
                .param("id", TEACHER_ID.toString()).update();
        jdbc.sql("INSERT INTO teacher_profile(user_id, staff_key) VALUES (:id, 'outside-risk-teacher-key')")
                .param("id", OUTSIDE_TEACHER_ID.toString()).update();
        jdbc.sql("""
                        INSERT INTO student_profile(
                            user_id, synthetic, synthetic_key, split_name, student_number,
                            college, major, cohort_year, class_name)
                        VALUES (:id, TRUE, 'risk-student-key', 'test', 'RISK-STUDENT',
                            'Engineering', 'Software', 2026, 'Software 1')
                        """).param("id", STUDENT_ID.toString()).update();
        jdbc.sql("""
                        INSERT INTO course(
                            id, code, title, term_label, starts_on, data_version, status, credits)
                        VALUES (:id, 'RISK-COURSE', 'Risk Course', '2026 Fall', '2026-09-01',
                            'risk-test', 'PUBLISHED', 3.0)
                        """).param("id", COURSE_ID.toString()).update();
        jdbc.sql("""
                        INSERT INTO teaching_assignment(course_id, teacher_id, assignment_role)
                        VALUES (:courseId, :teacherId, 'OWNER')
                        """).param("courseId", COURSE_ID.toString()).param("teacherId", TEACHER_ID.toString()).update();
        seedCurrentTwin("MEDIUM");
    }

    @Test
    void managerLifecycleKeepsInternalNotesOutOfStudentProjection() {
        RiskDtos.CaseDetail created = service.createManualCase(
                teacher(), new RiskDtos.CreateCaseRequest(
                        COURSE_ID, STUDENT_ID, "Follow-up", "Medium risk requires follow-up."));
        RiskDtos.CaseDetail assigned = service.assign(
                teacher(), created.riskCase().caseId(),
                new RiskDtos.AssignmentRequest(TEACHER_ID, "Course owner", created.riskCase().version()));
        service.addNote(teacher(), created.riskCase().caseId(), new RiskDtos.NoteRequest("Internal context"));
        RiskDtos.CaseDetail withAction = service.addAction(
                teacher(), created.riskCase().caseId(),
                new RiskDtos.ActionRequest("Complete review", "Finish the assigned review.", OffsetDateTime.now().plusDays(2)));

        assertEquals(TEACHER_ID, assigned.riskCase().assignedTo());
        assertEquals(1, service.detail(teacher(), created.riskCase().caseId()).internalNotes().size());
        assertEquals(1, service.studentActions(student()).size());
        RiskDtos.StudentActionProjection projection = service.addFeedback(
                student(), withAction.actionItems().getFirst().actionItemId(),
                new RiskDtos.FeedbackRequest("Started the review."));
        assertEquals("IN_PROGRESS", projection.status());
        assertEquals(1, projection.feedback().size());

        RiskDtos.CaseDetail inProgress = service.transition(
                teacher(), created.riskCase().caseId(),
                new RiskDtos.StatusRequest("IN_PROGRESS", "Work started", assigned.riskCase().version()));
        assertEquals("IN_PROGRESS", inProgress.riskCase().status());
    }

    @Test
    void automaticHighRiskCreationIsIdempotentAndScopeProtected() {
        jdbc.sql("UPDATE risk_prediction SET risk_band = 'HIGH' WHERE id = :id")
                .param("id", PREDICTION_ID.toString()).update();
        service.createCasesForCurrentHighRisk();
        service.createCasesForCurrentHighRisk();

        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM risk_case WHERE trigger_type = 'AUTO_HIGH'")
                .query(Integer.class).single());
        RiskDtos.CasePage page = service.cases(teacher(), null, null, 0, 25);
        assertEquals(1, page.total());
        assertEquals("高风险学情干预跟进", page.items().getFirst().title());
        assertEquals("当前学习孪生快照显示为高风险状态，需及时开展干预与跟进。",
                page.items().getFirst().summary());
        assertNotNull(page.items().getFirst().updatedAt());
        assertThrows(DomainException.class, () -> service.detail(
                outsideTeacher(), page.items().getFirst().caseId()));
    }

    private void seedCurrentTwin(String riskBand) {
        UUID questionId = uuid("a1000000-0000-0000-0000-000000000030");
        UUID answerId = uuid("a1000000-0000-0000-0000-000000000031");
        UUID jobId = uuid("a1000000-0000-0000-0000-000000000032");
        UUID snapshotId = uuid("a1000000-0000-0000-0000-000000000033");
        jdbc.sql("""
                        INSERT INTO question(
                            id, source_problem_key, prompt_text, answer_type, options_json,
                            correct_answer, difficulty, data_version, active)
                        VALUES (:id, 'risk-question', 'Risk fixture?', 'SINGLE_CHOICE',
                            JSON_OBJECT('A', 'Yes', 'B', 'No'), 'A', 0.5, 'risk-test', TRUE)
                        """).param("id", questionId.toString()).update();
        jdbc.sql("""
                        INSERT INTO answer_event(
                            id, course_id, student_id, question_id, submitted_answer, correct,
                            response_time_ms, attempt_number, event_sequence, occurred_at, data_version)
                        VALUES (:id, :courseId, :studentId, :questionId, 'A', TRUE,
                            100, 1, 1, CURRENT_TIMESTAMP(6), 'risk-test')
                        """).param("id", answerId.toString()).param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString()).param("questionId", questionId.toString()).update();
        jdbc.sql("""
                        INSERT INTO analysis_job(
                            id, answer_event_id, course_id, student_id, target_snapshot_id, correlation_id,
                            status, stage, degraded, degraded_stages, data_versions,
                            requested_model_versions, effective_model_versions, completed_at)
                        VALUES (:id, :answerId, :courseId, :studentId, :snapshotId, UUID(),
                            'COMPLETED', 'COMPLETED', FALSE, JSON_ARRAY(), JSON_ARRAY(),
                            JSON_ARRAY(), JSON_ARRAY(), CURRENT_TIMESTAMP(6))
                        """).param("id", jobId.toString()).param("answerId", answerId.toString())
                .param("courseId", COURSE_ID.toString()).param("studentId", STUDENT_ID.toString())
                .param("snapshotId", snapshotId.toString()).update();
        jdbc.sql("""
                        INSERT INTO risk_prediction(
                            id, analysis_job_id, answer_event_id, course_id, student_id,
                            calibrated_probability, risk_band, base_value, feature_values,
                            model_version, data_version, calibrated, degraded)
                        VALUES (:id, :jobId, :answerId, :courseId, :studentId,
                            0.65, :riskBand, 0.1, JSON_OBJECT(), 'risk-v1', 'risk-test', TRUE, FALSE)
                        """).param("id", PREDICTION_ID.toString()).param("jobId", jobId.toString())
                .param("answerId", answerId.toString()).param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString()).param("riskBand", riskBand).update();
        jdbc.sql("""
                        INSERT INTO twin_snapshot(
                            id, course_id, student_id, snapshot_version, answer_event_id, analysis_job_id,
                            knowledge_mastery, next_correct_probability, risk_probability,
                            engagement_score, persistence_score, plan_completion_rate,
                            data_versions, model_versions, degraded)
                        VALUES (:id, :courseId, :studentId, 1, :answerId, :jobId,
                            JSON_OBJECT(), 0.5, 0.65, 0.5, 0.5, 0,
                            JSON_ARRAY(), JSON_ARRAY(), FALSE)
                        """).param("id", snapshotId.toString()).param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString()).param("answerId", answerId.toString())
                .param("jobId", jobId.toString()).update();
        jdbc.sql("""
                        INSERT INTO twin_current_pointer(
                            course_id, student_id, snapshot_id, last_applied_answer_sequence)
                        VALUES (:courseId, :studentId, :snapshotId, 1)
                        """).param("courseId", COURSE_ID.toString()).param("studentId", STUDENT_ID.toString())
                .param("snapshotId", snapshotId.toString()).update();
    }

    private void account(UUID id, String username, String displayName) {
        jdbc.sql("""
                        INSERT INTO user_account(id, username, password_hash, display_name, enabled)
                        VALUES (:id, :username, 'not-used', :displayName, TRUE)
                        """).param("id", id.toString()).param("username", username)
                .param("displayName", displayName).update();
    }

    private static EduTwinPrincipal teacher() {
        return principal(TEACHER_ID, "risk-teacher", UserRole.TEACHER, Set.of(COURSE_ID));
    }
    private static EduTwinPrincipal outsideTeacher() {
        return principal(OUTSIDE_TEACHER_ID, "outside-risk-teacher", UserRole.TEACHER, Set.of(COURSE_ID));
    }
    private static EduTwinPrincipal student() {
        return principal(STUDENT_ID, "risk-student", UserRole.STUDENT, Set.of(COURSE_ID));
    }
    private static EduTwinPrincipal principal(UUID id, String username, UserRole role, Set<UUID> courses) {
        return new EduTwinPrincipal(id, username, username, "", role, courses, true);
    }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
