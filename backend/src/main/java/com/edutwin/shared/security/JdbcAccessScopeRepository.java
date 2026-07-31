package com.edutwin.shared.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccessScopeRepository implements AccessScopeRepository {

    private static final String STUDENT_COURSE_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM user_account u
                JOIN user_role ur
                  ON ur.user_id = u.id AND ur.role_code = 'STUDENT'
                JOIN course_enrollment e
                  ON e.student_id = u.id AND e.status = 'ACTIVE'
                WHERE u.id = :studentId
                  AND u.enabled = TRUE
                  AND e.course_id = :courseId
            ) AS allowed
            """;

    private static final String TEACHER_COURSE_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM user_account u
                JOIN user_role ur
                  ON ur.user_id = u.id AND ur.role_code = 'TEACHER'
                JOIN teaching_assignment a ON a.teacher_id = u.id
                WHERE u.id = :teacherId
                  AND u.enabled = TRUE
                  AND a.course_id = :courseId
            ) AS allowed
            """;

    private static final String TEACHER_STUDENT_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM user_account u
                JOIN user_role ur
                  ON ur.user_id = u.id AND ur.role_code = 'TEACHER'
                JOIN teaching_assignment a ON a.teacher_id = u.id
                JOIN course_enrollment e ON e.course_id = a.course_id
                WHERE u.id = :teacherId
                  AND u.enabled = TRUE
                  AND a.course_id = :courseId
                  AND e.student_id = :studentId
            ) AS allowed
            """;

    private static final String JOB_SCOPE_SQL = """
            SELECT id, course_id, student_id
            FROM analysis_job
            WHERE id = :jobId
            """;

    private static final String STUDENT_JOB_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM analysis_job j
                JOIN user_account u ON u.id = j.student_id
                JOIN user_role ur
                  ON ur.user_id = u.id AND ur.role_code = 'STUDENT'
                JOIN course_enrollment e
                  ON e.course_id = j.course_id
                 AND e.student_id = j.student_id
                 AND e.status = 'ACTIVE'
                WHERE j.id = :jobId
                  AND j.student_id = :studentId
                  AND u.enabled = TRUE
            ) AS allowed
            """;

    private static final String TEACHER_JOB_SQL = """
            SELECT EXISTS (
                SELECT 1
                FROM analysis_job j
                JOIN user_account u ON u.id = :teacherId
                JOIN user_role ur
                  ON ur.user_id = u.id AND ur.role_code = 'TEACHER'
                JOIN teaching_assignment a
                  ON a.course_id = j.course_id AND a.teacher_id = u.id
                JOIN course_enrollment e
                  ON e.course_id = j.course_id AND e.student_id = j.student_id
                WHERE j.id = :jobId
                  AND u.enabled = TRUE
            ) AS allowed
            """;

    private final JdbcClient jdbcClient;

    public JdbcAccessScopeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean studentHasActiveEnrollment(UUID studentId, UUID courseId) {
        return jdbcClient.sql(STUDENT_COURSE_SQL)
                .param("studentId", studentId.toString())
                .param("courseId", courseId.toString())
                .query((resultSet, rowNum) -> resultSet.getBoolean("allowed"))
                .single();
    }

    @Override
    public boolean teacherHasTeachingAssignment(UUID teacherId, UUID courseId) {
        return jdbcClient.sql(TEACHER_COURSE_SQL)
                .param("teacherId", teacherId.toString())
                .param("courseId", courseId.toString())
                .query((resultSet, rowNum) -> resultSet.getBoolean("allowed"))
                .single();
    }

    @Override
    public boolean teacherCanAccessStudent(UUID teacherId, UUID courseId, UUID studentId) {
        return jdbcClient.sql(TEACHER_STUDENT_SQL)
                .param("teacherId", teacherId.toString())
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNum) -> resultSet.getBoolean("allowed"))
                .single();
    }

    @Override
    public Optional<JobScope> findJobScope(UUID jobId) {
        return jdbcClient.sql(JOB_SCOPE_SQL)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNum) -> new JobScope(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("course_id")),
                        UUID.fromString(resultSet.getString("student_id"))))
                .optional();
    }

    @Override
    public boolean studentCanAccessJob(UUID studentId, UUID jobId) {
        return jdbcClient.sql(STUDENT_JOB_SQL)
                .param("studentId", studentId.toString())
                .param("jobId", jobId.toString())
                .query((resultSet, rowNum) -> resultSet.getBoolean("allowed"))
                .single();
    }

    @Override
    public boolean teacherCanAccessJob(UUID teacherId, UUID jobId) {
        return jdbcClient.sql(TEACHER_JOB_SQL)
                .param("teacherId", teacherId.toString())
                .param("jobId", jobId.toString())
                .query((resultSet, rowNum) -> resultSet.getBoolean("allowed"))
                .single();
    }
}
