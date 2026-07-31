package com.edutwin.identity;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {

    private static final UUID STUDENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TEACHER_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID COURSE_ID = UUID.fromString("10000000-0000-0000-0000-000000000010");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void seedIdentityAndTeachingFacts() {
        String databaseName = jdbcClient.sql("SELECT DATABASE()").query(String.class).single();
        assertEquals("edutwin_test", databaseName, "integration tests require edutwin_test");
        jdbcClient.sql("DELETE FROM admin_audit_event").update();
        jdbcClient.sql("DELETE FROM course_enrollment").update();
        jdbcClient.sql("DELETE FROM teaching_assignment").update();
        jdbcClient.sql("DELETE FROM course").update();
        jdbcClient.sql("DELETE FROM student_profile").update();
        jdbcClient.sql("DELETE FROM teacher_profile").update();
        jdbcClient.sql("DELETE FROM user_role").update();
        jdbcClient.sql("DELETE FROM user_account").update();
        jdbcClient.sql("DELETE FROM role_definition").update();

        jdbcClient.sql("INSERT INTO role_definition(code, description) VALUES ('STUDENT', 'Student'), ('TEACHER', 'Teacher')")
                .update();
        insertAccount(STUDENT_ID, "student", "Student Demo", "student-pass");
        insertAccount(TEACHER_ID, "teacher", "Teacher Demo", "teacher-pass");
        jdbcClient.sql("INSERT INTO user_role(user_id, role_code) VALUES (:studentId, 'STUDENT'), (:teacherId, 'TEACHER')")
                .param("studentId", STUDENT_ID.toString())
                .param("teacherId", TEACHER_ID.toString())
                .update();
        jdbcClient.sql("INSERT INTO student_profile(user_id, synthetic, synthetic_key, split_name) VALUES (:id, TRUE, 'synthetic-1', 'test')")
                .param("id", STUDENT_ID.toString())
                .update();
        jdbcClient.sql("INSERT INTO teacher_profile(user_id, staff_key) VALUES (:id, 'teacher-1')")
                .param("id", TEACHER_ID.toString())
                .update();
        jdbcClient.sql("""
                INSERT INTO course(id, code, title, term_label, starts_on, data_version, credits)
                VALUES (:id, 'MATH-1', 'Mathematics Foundations', '2025J', '2025-01-01', 'demo-v1', 3.0)
                """)
                .param("id", COURSE_ID.toString())
                .update();
        jdbcClient.sql("INSERT INTO course_enrollment(course_id, student_id, status) VALUES (:courseId, :studentId, 'ACTIVE')")
                .param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString())
                .update();
        jdbcClient.sql("INSERT INTO teaching_assignment(course_id, teacher_id, assignment_role) VALUES (:courseId, :teacherId, 'OWNER')")
                .param("courseId", COURSE_ID.toString())
                .param("teacherId", TEACHER_ID.toString())
                .update();
    }

    @Test
    void studentLoginIssuesJwtAndLimitsCoursesToEnrollment() throws Exception {
        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student","password":"student-pass"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.role").value("STUDENT"))
                .andExpect(jsonPath("$.user.accessibleCourseIds", contains(COURSE_ID.toString())))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(loginResponse)
                .get("accessToken")
                .asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(STUDENT_ID.toString()))
                .andExpect(jsonPath("$.role").value("STUDENT"));

        mockMvc.perform(get("/api/v1/courses").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].courseId").value(COURSE_ID.toString()))
                .andExpect(jsonPath("$.items[0].role").value("STUDENT"));
    }

    @Test
    void invalidPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"student","password":"wrong-pass"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.violations", hasSize(0)));
    }

    @Test
    void missingJwtReturnsContractProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void passwordChangesRecordSanitizedSuccessAndFailureAudits() throws Exception {
        String token = login("student-pass");
        String correlationId = "a2000000-0000-0000-0000-000000000001";

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Correlation-ID", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"student-pass","newPassword":"student-password-updated"}
                                """))
                .andExpect(status().isNoContent());

        String refreshed = login("student-password-updated");
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + refreshed)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrong-password","newPassword":"another-valid-password"}
                                """))
                .andExpect(status().isUnauthorized());

        var audits = jdbcClient.sql("""
                SELECT outcome, error_code, CAST(before_json AS CHAR) before_json,
                       CAST(after_json AS CHAR) after_json, correlation_id
                FROM admin_audit_event WHERE actor_user_id = :id AND action = 'PASSWORD_CHANGED'
                ORDER BY created_at
                """).param("id", STUDENT_ID.toString())
                .query((rs, row) -> new PasswordAudit(rs.getString("outcome"),
                        rs.getString("error_code"), rs.getString("before_json"),
                        rs.getString("after_json"), rs.getString("correlation_id"))).list();
        assertEquals(2, audits.size());
        assertEquals(2, jdbcClient.sql(
                "SELECT COUNT(*) FROM admin_audit_event WHERE actor_user_id = :id")
                .param("id", STUDENT_ID.toString()).query(Integer.class).single());
        assertEquals("SUCCEEDED", audits.get(0).outcome());
        assertEquals(correlationId, audits.get(0).correlationId());
        assertEquals("FAILED", audits.get(1).outcome());
        for (PasswordAudit audit : audits) {
            String summary = String.valueOf(audit.beforeJson()) + audit.afterJson();
            org.junit.jupiter.api.Assertions.assertFalse(summary.contains("student-pass"));
            org.junit.jupiter.api.Assertions.assertFalse(summary.contains("another-valid-password"));
        }
    }

    private String login(String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .get("accessToken").asText();
    }

    private record PasswordAudit(String outcome, String errorCode, String beforeJson,
            String afterJson, String correlationId) {}

    private void insertAccount(UUID id, String username, String displayName, String password) {
        jdbcClient.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled)
                VALUES (:id, :username, :passwordHash, :displayName, TRUE)
                """)
                .param("id", id.toString())
                .param("username", username)
                .param("passwordHash", passwordEncoder.encode(password))
                .param("displayName", displayName)
                .update();
    }
}
