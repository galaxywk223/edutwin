package com.edutwin.counselor;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CounselorIntegrationTest {
    private static final UUID COUNSELOR_ID = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNED_STUDENT_ID = UUID.fromString("91000000-0000-0000-0000-000000000002");
    private static final UUID OUT_OF_SCOPE_STUDENT_ID = UUID.fromString("91000000-0000-0000-0000-000000000003");
    private static final UUID COURSE_A_ID = UUID.fromString("91000000-0000-0000-0000-000000000011");
    private static final UUID COURSE_B_ID = UUID.fromString("91000000-0000-0000-0000-000000000012");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private CounselorService service;

    @BeforeEach
    void seedCounselorScopeAndMultipleCourses() {
        jdbc.sql("""
                INSERT INTO role_definition(code, description)
                VALUES ('COUNSELOR', 'Counselor'), ('STUDENT', 'Student')
                ON DUPLICATE KEY UPDATE description = VALUES(description)
                """).update();
        insertAccount(COUNSELOR_ID, "counselor-test", "Counselor Test");
        insertAccount(ASSIGNED_STUDENT_ID, "assigned-student", "Assigned Student");
        insertAccount(OUT_OF_SCOPE_STUDENT_ID, "outside-student", "Outside Student");
        jdbc.sql("""
                INSERT INTO user_role(user_id, role_code) VALUES
                  (:counselorId, 'COUNSELOR'),
                  (:assignedId, 'STUDENT'),
                  (:outsideId, 'STUDENT')
                """)
                .param("counselorId", COUNSELOR_ID.toString())
                .param("assignedId", ASSIGNED_STUDENT_ID.toString())
                .param("outsideId", OUT_OF_SCOPE_STUDENT_ID.toString())
                .update();
        jdbc.sql("INSERT INTO counselor_profile(user_id, staff_number) VALUES (:id, 'COUNSELOR-TEST')")
                .param("id", COUNSELOR_ID.toString()).update();
        insertStudent(ASSIGNED_STUDENT_ID, "2026-01", "软件工程2026级01班");
        insertStudent(OUT_OF_SCOPE_STUDENT_ID, "2026-02", "软件工程2026级02班");
        jdbc.sql("""
                INSERT INTO counselor_scope(
                    counselor_id, scope_type, scope_key, college, major,
                    cohort_year, class_name, created_by)
                VALUES (:id, 'CLASS', 'CLASS|计算机学院|软件工程|2026|01',
                        '计算机学院', '软件工程', 2026, '软件工程2026级01班', :id)
                """).param("id", COUNSELOR_ID.toString()).update();
        insertCourse(COURSE_A_ID, "COUNSELOR-A", "软件工程基础", 2.5);
        insertCourse(COURSE_B_ID, "COUNSELOR-B", "软件项目管理", 3.5);
        jdbc.sql("""
                INSERT INTO course_enrollment(course_id, student_id, status) VALUES
                  (:courseA, :studentId, 'ACTIVE'),
                  (:courseB, :studentId, 'COMPLETED')
                """)
                .param("courseA", COURSE_A_ID.toString())
                .param("courseB", COURSE_B_ID.toString())
                .param("studentId", ASSIGNED_STUDENT_ID.toString())
                .update();
    }

    @Test
    void assignedStudentListAndOverviewExposeCrossCourseStatisticsOnly() throws Exception {
        mockMvc.perform(get("/api/v1/counselor/students").with(authentication(counselorAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].studentId").value(ASSIGNED_STUDENT_ID.toString()))
                .andExpect(jsonPath("$.items[0].courseCount").value(2))
                .andExpect(jsonPath("$.items[0].totalCredits").value(6.0));

        mockMvc.perform(get("/api/v1/counselor/students/{studentId}/overview", ASSIGNED_STUDENT_ID)
                        .with(authentication(counselorAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses", hasSize(2)))
                .andExpect(jsonPath("$.courses[0].code").exists())
                .andExpect(jsonPath("$.courses[0].credits").isNumber());

        mockMvc.perform(get("/api/v1/counselor/students/{studentId}/overview", OUT_OF_SCOPE_STUDENT_ID)
                        .with(authentication(counselorAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void counselorCannotReadRawTwinOrUseStudentMutationEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/courses/{courseId}/students/{studentId}/twin/current",
                        COURSE_A_ID, ASSIGNED_STUDENT_ID).with(authentication(counselorAuth())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/courses/{courseId}/answers", COURSE_A_ID)
                        .with(authentication(counselorAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void counselorCanCompareAtMostFourAssignedClasses() throws Exception {
        CounselorDtos.ClassComparisonResult result = service.compareClasses(
                COUNSELOR_ID, java.util.List.of("软件工程2026级01班"));
        org.junit.jupiter.api.Assertions.assertEquals(1, result.classes().size());
        org.junit.jupiter.api.Assertions.assertEquals(1, result.classes().getFirst().studentCount());

        mockMvc.perform(get("/api/v1/counselor/classes/compare")
                        .param("classNames", "A", "B", "C", "D", "E")
                        .with(authentication(counselorAuth())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUNSELOR_CLASS_COMPARISON_INVALID"));
    }

    private void insertAccount(UUID id, String username, String displayName) {
        jdbc.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled)
                VALUES (:id, :username, 'not-used', :displayName, TRUE)
                """).param("id", id.toString()).param("username", username)
                .param("displayName", displayName).update();
    }

    private void insertStudent(UUID id, String syntheticKey, String className) {
        jdbc.sql("""
                INSERT INTO student_profile(
                    user_id, synthetic, synthetic_key, split_name, student_number,
                    college, major, cohort_year, class_name)
                VALUES (:id, TRUE, :syntheticKey, 'test', :syntheticKey,
                        '计算机学院', '软件工程', 2026, :className)
                """).param("id", id.toString()).param("syntheticKey", syntheticKey)
                .param("className", className).update();
    }

    private void insertCourse(UUID id, String code, String title, double credits) {
        jdbc.sql("""
                INSERT INTO course(
                    id, code, title, term_label, starts_on, data_version, status, credits)
                VALUES (:id, :code, :title, '2026 秋', '2026-09-01', 'counselor-test', 'PUBLISHED', :credits)
                """).param("id", id.toString()).param("code", code).param("title", title)
                .param("credits", credits).update();
    }

    private static UsernamePasswordAuthenticationToken counselorAuth() {
        EduTwinPrincipal principal = new EduTwinPrincipal(
                COUNSELOR_ID, "counselor-test", "Counselor Test", "",
                UserRole.COUNSELOR, Set.of(), true);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, "", principal.getAuthorities());
    }
}
