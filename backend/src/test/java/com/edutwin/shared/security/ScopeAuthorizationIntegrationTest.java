package com.edutwin.shared.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@SpringBootTest(properties = {
    "spring.datasource.url=${EDUTWIN_DB_URL:"
            + "jdbc:mysql://127.0.0.1:33306/edutwin_test?useUnicode=true"
            + "&characterEncoding=utf8&serverTimezone=UTC"
            + "&allowPublicKeyRetrieval=true&useSSL=false}",
    "spring.datasource.username=${EDUTWIN_DB_USER:edutwin}",
    "spring.datasource.password=${EDUTWIN_DB_PASSWORD:edutwin-local}",
    "edutwin.ai.enabled=false"
})
class ScopeAuthorizationIntegrationTest {

    private static final UUID STUDENT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_STUDENT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000002");
    private static final UUID INACTIVE_STUDENT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000003");
    private static final UUID CROSS_COURSE_STUDENT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000004");
    private static final UUID TEACHER_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000010");
    private static final UUID TAUGHT_COURSE_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000020");
    private static final UUID CROSS_COURSE_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000021");
    private static final UUID TAUGHT_QUESTION_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000030");
    private static final UUID CROSS_QUESTION_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000031");
    private static final UUID TAUGHT_ANSWER_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000040");
    private static final UUID CROSS_ANSWER_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000041");
    private static final UUID TAUGHT_JOB_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000050");
    private static final UUID CROSS_JOB_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000051");
    private static final UUID TAUGHT_TARGET_SNAPSHOT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000060");
    private static final UUID CROSS_TARGET_SNAPSHOT_ID =
            UUID.fromString("72000000-0000-0000-0000-000000000061");

    @Autowired
    private ScopeAuthorization authorization;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void seedScopedFacts() {
        requireTestDatabase();
        cleanupScopedFacts();
        seedRolesAndAccounts();
        seedCoursesAndMemberships();
        seedJobs();
    }

    @AfterEach
    void removeScopedFacts() {
        requireTestDatabase();
        cleanupScopedFacts();
    }

    @Test
    void studentCanAccessOnlySelfWithActiveEnrollment() {
        Authentication student = authentication(
                STUDENT_ID, UserRole.STUDENT, Set.of(TAUGHT_COURSE_ID, CROSS_COURSE_ID));
        Authentication other =
                authentication(OTHER_STUDENT_ID, UserRole.STUDENT, Set.of(TAUGHT_COURSE_ID));
        Authentication inactive =
                authentication(INACTIVE_STUDENT_ID, UserRole.STUDENT, Set.of(TAUGHT_COURSE_ID));

        assertTrue(authorization.canAccessCourse(student, TAUGHT_COURSE_ID));
        assertTrue(authorization.canAccessStudent(student, TAUGHT_COURSE_ID, STUDENT_ID));
        assertFalse(authorization.canAccessStudent(student, TAUGHT_COURSE_ID, OTHER_STUDENT_ID));
        assertFalse(authorization.canAccessCourse(student, CROSS_COURSE_ID));
        assertFalse(authorization.canAccessStudent(other, TAUGHT_COURSE_ID, STUDENT_ID));
        assertFalse(authorization.canAccessCourse(inactive, TAUGHT_COURSE_ID));
        assertFalse(
                authorization.canAccessStudent(
                        inactive, TAUGHT_COURSE_ID, INACTIVE_STUDENT_ID));
    }

    @Test
    void teacherRequiresDatabaseTeachingAssignmentAndCourseMembership() {
        Authentication teacher = authentication(
                TEACHER_ID, UserRole.TEACHER, Set.of(TAUGHT_COURSE_ID, CROSS_COURSE_ID));
        Authentication missingClaim = authentication(TEACHER_ID, UserRole.TEACHER, Set.of());

        assertTrue(authorization.canAccessCourse(teacher, TAUGHT_COURSE_ID));
        assertTrue(authorization.canAccessStudent(teacher, TAUGHT_COURSE_ID, STUDENT_ID));
        assertTrue(
                authorization.canAccessStudent(teacher, TAUGHT_COURSE_ID, OTHER_STUDENT_ID));
        assertFalse(authorization.canAccessCourse(teacher, CROSS_COURSE_ID));
        assertFalse(
                authorization.canAccessStudent(
                        teacher, CROSS_COURSE_ID, CROSS_COURSE_STUDENT_ID));
        assertFalse(authorization.canAccessCourse(missingClaim, TAUGHT_COURSE_ID));
    }

    @Test
    void jobAccessUsesPersistedCourseAndStudentScope() {
        Authentication student =
                authentication(STUDENT_ID, UserRole.STUDENT, Set.of(TAUGHT_COURSE_ID));
        Authentication other =
                authentication(OTHER_STUDENT_ID, UserRole.STUDENT, Set.of(TAUGHT_COURSE_ID));
        Authentication teacher = authentication(
                TEACHER_ID, UserRole.TEACHER, Set.of(TAUGHT_COURSE_ID, CROSS_COURSE_ID));

        assertTrue(authorization.canAccessJob(student, TAUGHT_JOB_ID));
        assertFalse(authorization.canAccessJob(other, TAUGHT_JOB_ID));
        assertFalse(authorization.canAccessJob(student, CROSS_JOB_ID));
        assertTrue(authorization.canAccessJob(teacher, TAUGHT_JOB_ID));
        assertFalse(authorization.canAccessJob(teacher, CROSS_JOB_ID));
        assertFalse(authorization.canAccessJob(teacher, UUID.randomUUID()));
    }

    private void requireTestDatabase() {
        String databaseName = jdbcClient.sql("SELECT DATABASE()").query(String.class).single();
        if (!"edutwin_test".equals(databaseName)) {
            throw new IllegalStateException(
                    "ScopeAuthorizationIntegrationTest requires edutwin_test, got "
                            + databaseName);
        }
    }

    private void seedRolesAndAccounts() {
        jdbcClient.sql("""
                INSERT IGNORE INTO role_definition(code, description)
                VALUES ('STUDENT', 'Student'), ('TEACHER', 'Teacher')
                """).update();
        insertAccount(STUDENT_ID, "scope-student");
        insertAccount(OTHER_STUDENT_ID, "scope-other-student");
        insertAccount(INACTIVE_STUDENT_ID, "scope-inactive-student");
        insertAccount(CROSS_COURSE_STUDENT_ID, "scope-cross-student");
        insertAccount(TEACHER_ID, "scope-teacher");
        insertRole(STUDENT_ID, "STUDENT");
        insertRole(OTHER_STUDENT_ID, "STUDENT");
        insertRole(INACTIVE_STUDENT_ID, "STUDENT");
        insertRole(CROSS_COURSE_STUDENT_ID, "STUDENT");
        insertRole(TEACHER_ID, "TEACHER");
        insertStudentProfile(STUDENT_ID, "scope-student");
        insertStudentProfile(OTHER_STUDENT_ID, "scope-other-student");
        insertStudentProfile(INACTIVE_STUDENT_ID, "scope-inactive-student");
        insertStudentProfile(CROSS_COURSE_STUDENT_ID, "scope-cross-student");
        jdbcClient.sql("""
                INSERT INTO teacher_profile(user_id, staff_key)
                VALUES (:id, 'scope-teacher')
                """)
                .param("id", TEACHER_ID.toString())
                .update();
    }

    private void seedCoursesAndMemberships() {
        insertCourse(TAUGHT_COURSE_ID, "SCOPE-A", "2025J");
        insertCourse(CROSS_COURSE_ID, "SCOPE-B", "2025B");
        insertEnrollment(TAUGHT_COURSE_ID, STUDENT_ID, "ACTIVE");
        insertEnrollment(TAUGHT_COURSE_ID, OTHER_STUDENT_ID, "ACTIVE");
        insertEnrollment(TAUGHT_COURSE_ID, INACTIVE_STUDENT_ID, "COMPLETED");
        insertEnrollment(CROSS_COURSE_ID, CROSS_COURSE_STUDENT_ID, "ACTIVE");
        jdbcClient.sql("""
                INSERT INTO teaching_assignment(course_id, teacher_id, assignment_role)
                VALUES (:courseId, :teacherId, 'OWNER')
                """)
                .param("courseId", TAUGHT_COURSE_ID.toString())
                .param("teacherId", TEACHER_ID.toString())
                .update();
        insertQuestion(TAUGHT_QUESTION_ID, "scope-question-a");
        insertQuestion(CROSS_QUESTION_ID, "scope-question-b");
    }

    private void seedJobs() {
        insertAnswer(
                TAUGHT_ANSWER_ID,
                TAUGHT_COURSE_ID,
                STUDENT_ID,
                TAUGHT_QUESTION_ID,
                1);
        insertAnswer(
                CROSS_ANSWER_ID,
                CROSS_COURSE_ID,
                CROSS_COURSE_STUDENT_ID,
                CROSS_QUESTION_ID,
                1);
        insertJob(
                TAUGHT_JOB_ID,
                TAUGHT_ANSWER_ID,
                TAUGHT_COURSE_ID,
                STUDENT_ID,
                TAUGHT_TARGET_SNAPSHOT_ID);
        insertJob(
                CROSS_JOB_ID,
                CROSS_ANSWER_ID,
                CROSS_COURSE_ID,
                CROSS_COURSE_STUDENT_ID,
                CROSS_TARGET_SNAPSHOT_ID);
    }

    private void insertAccount(UUID id, String username) {
        jdbcClient.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled)
                VALUES (:id, :username, 'unused-test-hash', :username, TRUE)
                """)
                .param("id", id.toString())
                .param("username", username)
                .update();
    }

    private void insertRole(UUID userId, String role) {
        jdbcClient.sql("INSERT INTO user_role(user_id, role_code) VALUES (:userId, :role)")
                .param("userId", userId.toString())
                .param("role", role)
                .update();
    }

    private void insertStudentProfile(UUID userId, String syntheticKey) {
        jdbcClient.sql("""
                INSERT INTO student_profile(user_id, synthetic, synthetic_key, split_name)
                VALUES (:userId, TRUE, :syntheticKey, 'test')
                """)
                .param("userId", userId.toString())
                .param("syntheticKey", syntheticKey)
                .update();
    }

    private void insertCourse(UUID courseId, String code, String presentation) {
        jdbcClient.sql("""
                INSERT INTO course(id, code, title, term_label, starts_on, data_version, credits)
                VALUES (:id, :code, :code, :presentation, '2025-01-01', 'scope-v1', 3.0)
                """)
                .param("id", courseId.toString())
                .param("code", code)
                .param("presentation", presentation)
                .update();
    }

    private void insertEnrollment(UUID courseId, UUID studentId, String status) {
        jdbcClient.sql("""
                INSERT INTO course_enrollment(course_id, student_id, status)
                VALUES (:courseId, :studentId, :status)
                """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("status", status)
                .update();
    }

    private void insertQuestion(UUID questionId, String sourceKey) {
        jdbcClient.sql("""
                INSERT INTO question(
                    id, source_problem_key, prompt_text, answer_type, correct_answer,
                    difficulty, data_version, active)
                VALUES (
                    :id, :sourceKey, 'Scope test question', 'TEXT', 'correct',
                    0.5, 'scope-v1', TRUE)
                """)
                .param("id", questionId.toString())
                .param("sourceKey", sourceKey)
                .update();
    }

    private void insertAnswer(
            UUID answerId,
            UUID courseId,
            UUID studentId,
            UUID questionId,
            long eventSequence) {
        jdbcClient.sql("""
                INSERT INTO answer_event(
                    id, course_id, student_id, question_id, submitted_answer, correct,
                    response_time_ms, attempt_number, event_sequence, occurred_at, data_version)
                VALUES (
                    :id, :courseId, :studentId, :questionId, 'correct', TRUE,
                    100, 1, :eventSequence, CURRENT_TIMESTAMP(6), 'scope-v1')
                """)
                .param("id", answerId.toString())
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("questionId", questionId.toString())
                .param("eventSequence", eventSequence)
                .update();
    }

    private void insertJob(
            UUID jobId,
            UUID answerId,
            UUID courseId,
            UUID studentId,
            UUID targetSnapshotId) {
        jdbcClient.sql("""
                INSERT INTO analysis_job(
                    id, answer_event_id, course_id, student_id, target_snapshot_id,
                    correlation_id, status, stage, degraded, attempt_count,
                    data_versions, requested_model_versions, effective_model_versions, version)
                VALUES (
                    :id, :answerId, :courseId, :studentId, :targetSnapshotId,
                    :correlationId, 'QUEUED', 'QUEUED', FALSE, 0,
                    JSON_OBJECT('fusion', 'scope-v1'),
                    JSON_OBJECT('risk', 'risk-v1'),
                    JSON_OBJECT('risk', 'risk-v1'), 0)
                """)
                .param("id", jobId.toString())
                .param("answerId", answerId.toString())
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("targetSnapshotId", targetSnapshotId.toString())
                .param("correlationId", UUID.randomUUID().toString())
                .update();
    }

    private Authentication authentication(UUID userId, UserRole role, Set<UUID> courseClaims) {
        EduTwinPrincipal principal = new EduTwinPrincipal(
                userId,
                "scope-" + userId,
                "Scope Principal",
                "",
                role,
                courseClaims,
                true);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, "", principal.getAuthorities());
    }

    private void cleanupScopedFacts() {
        jdbcClient.sql("""
                DELETE FROM analysis_job
                WHERE id = :taughtJob OR id = :crossJob
                """)
                .param("taughtJob", TAUGHT_JOB_ID.toString())
                .param("crossJob", CROSS_JOB_ID.toString())
                .update();
        jdbcClient.sql("""
                DELETE FROM answer_event
                WHERE id = :taughtAnswer OR id = :crossAnswer
                """)
                .param("taughtAnswer", TAUGHT_ANSWER_ID.toString())
                .param("crossAnswer", CROSS_ANSWER_ID.toString())
                .update();
        jdbcClient.sql("""
                DELETE FROM teaching_assignment
                WHERE teacher_id = :teacherId
                  AND (course_id = :taughtCourse OR course_id = :crossCourse)
                """)
                .param("teacherId", TEACHER_ID.toString())
                .param("taughtCourse", TAUGHT_COURSE_ID.toString())
                .param("crossCourse", CROSS_COURSE_ID.toString())
                .update();
        jdbcClient.sql("""
                DELETE FROM course_enrollment
                WHERE course_id = :taughtCourse OR course_id = :crossCourse
                """)
                .param("taughtCourse", TAUGHT_COURSE_ID.toString())
                .param("crossCourse", CROSS_COURSE_ID.toString())
                .update();
        jdbcClient.sql("""
                DELETE FROM question
                WHERE id = :taughtQuestion OR id = :crossQuestion
                """)
                .param("taughtQuestion", TAUGHT_QUESTION_ID.toString())
                .param("crossQuestion", CROSS_QUESTION_ID.toString())
                .update();
        jdbcClient.sql("""
                DELETE FROM course
                WHERE id = :taughtCourse OR id = :crossCourse
                """)
                .param("taughtCourse", TAUGHT_COURSE_ID.toString())
                .param("crossCourse", CROSS_COURSE_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM teacher_profile WHERE user_id = :teacherId")
                .param("teacherId", TEACHER_ID.toString())
                .update();
        deleteStudents();
        jdbcClient.sql("""
                DELETE FROM user_role
                WHERE user_id = :studentId
                   OR user_id = :otherStudentId
                   OR user_id = :inactiveStudentId
                   OR user_id = :crossStudentId
                   OR user_id = :teacherId
                """)
                .param("studentId", STUDENT_ID.toString())
                .param("otherStudentId", OTHER_STUDENT_ID.toString())
                .param("inactiveStudentId", INACTIVE_STUDENT_ID.toString())
                .param("crossStudentId", CROSS_COURSE_STUDENT_ID.toString())
                .param("teacherId", TEACHER_ID.toString())
                .update();
        jdbcClient.sql("""
                DELETE FROM user_account
                WHERE id = :studentId
                   OR id = :otherStudentId
                   OR id = :inactiveStudentId
                   OR id = :crossStudentId
                   OR id = :teacherId
                """)
                .param("studentId", STUDENT_ID.toString())
                .param("otherStudentId", OTHER_STUDENT_ID.toString())
                .param("inactiveStudentId", INACTIVE_STUDENT_ID.toString())
                .param("crossStudentId", CROSS_COURSE_STUDENT_ID.toString())
                .param("teacherId", TEACHER_ID.toString())
                .update();
    }

    private void deleteStudents() {
        jdbcClient.sql("""
                DELETE FROM student_profile
                WHERE user_id = :studentId
                   OR user_id = :otherStudentId
                   OR user_id = :inactiveStudentId
                   OR user_id = :crossStudentId
                """)
                .param("studentId", STUDENT_ID.toString())
                .param("otherStudentId", OTHER_STUDENT_ID.toString())
                .param("inactiveStudentId", INACTIVE_STUDENT_ID.toString())
                .param("crossStudentId", CROSS_COURSE_STUDENT_ID.toString())
                .update();
    }
}
