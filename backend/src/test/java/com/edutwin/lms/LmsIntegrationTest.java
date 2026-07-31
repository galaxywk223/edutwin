package com.edutwin.lms;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.dashboard.TeacherDashboardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@AutoConfigureMockMvc
class LmsIntegrationTest {

    private static final UUID TEACHER_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID STUDENT_ID = UUID.fromString("81000000-0000-0000-0000-000000000002");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LmsLifecycleService lifecycleService;
    @MockitoSpyBean private TeacherDashboardService teacherDashboardService;

    @BeforeEach
    void seedIdentities() {
        assertEquals("edutwin_test", jdbcClient.sql("SELECT DATABASE()").query(String.class).single());
        cleanup();
        jdbcClient.sql("INSERT INTO role_definition(code, description) VALUES ('TEACHER','Teacher'),('STUDENT','Student')").update();
        jdbcClient.sql("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled) VALUES
                  (:teacherId, 'lms-teacher', 'unused', 'LMS Teacher', TRUE),
                  (:studentId, 'lms-student', 'unused', 'LMS Student', TRUE)
                """)
                .param("teacherId", TEACHER_ID.toString())
                .param("studentId", STUDENT_ID.toString())
                .update();
        jdbcClient.sql("""
                INSERT INTO user_role(user_id, role_code) VALUES
                  (:teacherId, 'TEACHER'), (:studentId, 'STUDENT')
                """)
                .param("teacherId", TEACHER_ID.toString())
                .param("studentId", STUDENT_ID.toString())
                .update();
        jdbcClient.sql("INSERT INTO teacher_profile(user_id, staff_key) VALUES (:id, 'LMS-T-1')")
                .param("id", TEACHER_ID.toString()).update();
        jdbcClient.sql("INSERT INTO student_profile(user_id, synthetic, synthetic_key, split_name) VALUES (:id, TRUE, 'LMS-S-1', 'test')")
                .param("id", STUDENT_ID.toString()).update();
        clearInvocations(teacherDashboardService);
    }

    @AfterEach
    void cleanupAfter() {
        cleanup();
    }

    @Test
    void teacherBuildsAndPublishesCourseThenStudentReadsIt() throws Exception {
        JsonNode course = json(mockMvc.perform(post("/api/v1/lms/teacher/courses")
                        .with(authentication(teacherAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"LMS-101","title":"LMS Foundations","termLabel":"2026J","credits":3.0,
                                 "description":"Course authoring test","startsOn":"2026-09-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
        String courseId = course.get("courseId").asText();

        JsonNode section = json(mockMvc.perform(post("/api/v1/lms/teacher/courses/{courseId}/sections", courseId)
                        .with(authentication(teacherAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Start\",\"description\":\"First section\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String sectionId = section.get("sectionId").asText();

        mockMvc.perform(post("/api/v1/lms/teacher/sections/{sectionId}/lessons", sectionId)
                        .with(authentication(teacherAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Lesson one","summary":"Summary","body":"Lesson body",
                                 "resourceUrl":null,"status":"PUBLISHED"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/roster/{studentId}", courseId, STUDENT_ID)
                        .with(authentication(teacherAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrolled\":true}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/status", courseId)
                        .with(authentication(teacherAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/lms/courses/{courseId}", courseId)
                        .with(authentication(studentAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections", hasSize(1)))
                .andExpect(jsonPath("$.sections[0].lessons", hasSize(1)))
                .andExpect(jsonPath("$.course.title").value("LMS Foundations"));
    }

    @Test
    void objectiveAssessmentScoresOnceAndReplaysTheSameIdempotencyKey() throws Exception {
        UUID courseId = seedPublishedCourse();
        String skillId = jdbcClient.sql("""
                SELECT qs.skill_id FROM course_question cq JOIN question_skill qs ON qs.question_id = cq.question_id
                WHERE cq.course_id = :courseId LIMIT 1
                """).param("courseId", courseId.toString()).query(String.class).single();
        JsonNode assessment = json(mockMvc.perform(post("/api/v1/lms/teacher/courses/{courseId}/assessments", courseId)
                        .with(authentication(teacherAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Check one","description":"Objective check","assessmentType":"QUIZ","dueAt":null,
                                 "questions":[{"prompt":"Choose A","options":[{"choiceId":"A","label":"A"},{"choiceId":"B","label":"B"}],
                                 "correctChoiceId":"A","points":2,"skillIds":["%s"]}]}
                                """.formatted(skillId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String assessmentId = assessment.get("assessmentId").asText();
        String questionId = assessment.get("questions").get(0).get("questionId").asText();

        mockMvc.perform(put("/api/v1/lms/teacher/assessments/{assessmentId}/status", assessmentId)
                        .with(authentication(teacherAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        String submission = "{\"answers\":[{\"questionId\":\"" + questionId
                + "\",\"selectedChoiceId\":\"A\"}]}";
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/api/v1/lms/assessments/{assessmentId}/submissions", assessmentId)
                            .with(authentication(studentAuth()))
                            .header("Idempotency-Key", "lms-test-key-0001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(submission))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.score").value(2))
                    .andExpect(jsonPath("$.answers[0].correctChoiceId").value("A"))
                    .andExpect(jsonPath("$.percentage").value(100));
        }
        assertEquals(1, jdbcClient.sql("SELECT COUNT(*) FROM lms_submission").query(Integer.class).single());

        mockMvc.perform(get("/api/v1/lms/assessments/{assessmentId}/submissions", assessmentId)
                        .with(authentication(studentAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answers[0].correctChoiceId").value("A"));

        mockMvc.perform(post("/api/v1/lms/assessments/{assessmentId}/submissions", assessmentId)
                        .with(authentication(studentAuth()))
                        .header("Idempotency-Key", "lms-test-key-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submission))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSESSMENT_ALREADY_SUBMITTED"));
    }

    @Test
    void courseContentAndEnrollmentLifecyclePreserveHistory() throws Exception {
        UUID courseId = seedPublishedCourse();
        long initialTokenVersion = tokenVersion(STUDENT_ID);

        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/status", courseId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStatus":"PUBLISHED","targetStatus":"ARCHIVED","reason":"Term ended"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/status", courseId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStatus":"ARCHIVED","targetStatus":"PUBLISHED","reason":"Invalid shortcut"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COURSE_STATUS_TRANSITION_INVALID"));
        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/status", courseId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedCurrentStatus":"ARCHIVED","targetStatus":"DRAFT","reason":"Prepare next term"}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        JsonNode section = json(mockMvc.perform(post("/api/v1/lms/teacher/courses/{courseId}/sections", courseId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Lifecycle\",\"description\":\"\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode lesson = json(mockMvc.perform(post("/api/v1/lms/teacher/sections/{sectionId}/lessons", section.get("sectionId").asText())
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Draft lesson\",\"summary\":\"\",\"body\":\"Body\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mockMvc.perform(put("/api/v1/lms/teacher/lessons/{lessonId}/status", lesson.get("lessonId").asText())
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"PUBLISHED\",\"expectedCurrentStatus\":\"DRAFT\",\"reason\":\"Ready\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/lms/teacher/lessons/{lessonId}", lesson.get("lessonId").asText())
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Changed\",\"summary\":\"\",\"body\":\"Changed\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTENT_LOCKED"));

        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/roster/{studentId}", courseId, STUDENT_ID)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrolled\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ENROLLMENT_REASON_REQUIRED"));
        assertEquals(initialTokenVersion, tokenVersion(STUDENT_ID));
        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/roster/{studentId}", courseId, STUDENT_ID)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrolled\":false,\"reason\":\"Transferred\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/roster/{studentId}", courseId, STUDENT_ID)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enrolled\":true,\"reason\":\"Returned\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/lms/teacher/courses/{courseId}/roster/{studentId}", courseId, STUDENT_ID)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enrolled\":true,\"reason\":\"Already active\"}"))
                .andExpect(status().isNoContent());
        assertEquals(initialTokenVersion + 2, tokenVersion(STUDENT_ID));
        verify(teacherDashboardService, times(2)).evict(courseId);
        assertEquals(2, jdbcClient.sql("SELECT COUNT(*) FROM lms_enrollment_history WHERE course_id=:id AND student_id=:studentId")
                .param("id", courseId.toString()).param("studentId", STUDENT_ID.toString()).query(Integer.class).single());
        assertEquals("RESTORED", jdbcClient.sql("SELECT action_type FROM lms_enrollment_history WHERE course_id=:id ORDER BY effective_at DESC,id DESC LIMIT 1")
                .param("id", courseId.toString()).query(String.class).single());
    }

    @Test
    void dueClosureExtensionAndApprovedRetakeProduceImmutableAttemptsAndHighestGrade() throws Exception {
        UUID courseId = seedPublishedCourse();
        JsonNode assessment = createAssessment(courseId, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        String assessmentId = assessment.get("assessmentId").asText();
        String questionId = assessment.get("questions").get(0).get("questionId").asText();
        mockMvc.perform(put("/api/v1/lms/teacher/assessments/{assessmentId}/status", assessmentId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"PUBLISHED\",\"expectedCurrentStatus\":\"DRAFT\",\"reason\":\"Open\"}"))
                .andExpect(status().isOk());

        jdbcClient.sql("UPDATE lms_assessment SET due_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE id=:id")
                .param("id", assessmentId).update();
        lifecycleService.closeExpiredAssessments();
        assertEquals("CLOSED", jdbcClient.sql("SELECT status FROM lms_assessment WHERE id=:id")
                .param("id", assessmentId).query(String.class).single());
        String extendedDueAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).toString();
        mockMvc.perform(post("/api/v1/lms/teacher/assessments/{assessmentId}/extend", assessmentId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueAt\":\"" + extendedDueAt + "\",\"reason\":\"Class extension\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));

        submit(assessmentId, questionId, "B", "attempt-initial")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.attemptNumber").value(1))
                .andExpect(jsonPath("$.score").value(0));
        JsonNode request = json(mockMvc.perform(post("/api/v1/lms/assessments/{assessmentId}/attempt-requests", assessmentId)
                        .with(authentication(studentAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestType\":\"RETAKE\",\"reason\":\"Need another attempt\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(put("/api/v1/lms/teacher/attempt-requests/{requestId}/decision", request.get("requestId").asText())
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\",\"reason\":\"Approved\",\"personalDueAt\":\""
                                + OffsetDateTime.now(ZoneOffset.UTC).plusDays(1) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        submit(assessmentId, questionId, "A", "attempt-retake")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.attemptNumber").value(2))
                .andExpect(jsonPath("$.attemptType").value("RETAKE")).andExpect(jsonPath("$.score").value(2));

        mockMvc.perform(get("/api/v1/lms/assessments/{assessmentId}/attempts", assessmentId)
                        .with(authentication(studentAuth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.currentScore").value(2));
        mockMvc.perform(get("/api/v1/lms/courses/{courseId}/grades", courseId)
                        .with(authentication(studentAuth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].score").value(2))
                .andExpect(jsonPath("$.items[0].attemptCount").value(2));

        mockMvc.perform(post("/api/v1/lms/teacher/assessments/{assessmentId}/cancel", assessmentId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Assessment invalidated\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/lms/courses/{courseId}/grades", courseId)
                        .with(authentication(studentAuth())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].score").value(nullValue()))
                .andExpect(jsonPath("$.items[0].submitted").value(false));
        assertEquals(2, jdbcClient.sql("SELECT COUNT(*) FROM lms_submission WHERE assessment_id=:id")
                .param("id", assessmentId).query(Integer.class).single());
    }

    private UUID seedPublishedCourse() {
        UUID courseId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO course(id, code, title, term_label, starts_on, data_version, description, status, credits)
                VALUES (:id, :code, 'Published course', '2026J', '2026-09-01', 'lms-test', '', 'PUBLISHED', 3.0)
                """)
                .param("id", courseId.toString())
                .param("code", "LMS-" + courseId.toString().substring(0, 8))
                .update();
        jdbcClient.sql("INSERT INTO teaching_assignment(course_id, teacher_id, assignment_role) VALUES (:courseId, :teacherId, 'OWNER')")
                .param("courseId", courseId.toString()).param("teacherId", TEACHER_ID.toString()).update();
        jdbcClient.sql("INSERT INTO course_enrollment(course_id, student_id, status) VALUES (:courseId, :studentId, 'ACTIVE')")
                .param("courseId", courseId.toString()).param("studentId", STUDENT_ID.toString()).update();
        UUID skillId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO knowledge_skill(id, source_skill_key, name, data_version, content_origin)
                VALUES (:id, :key, 'Test skill', 'lms-test', 'TEACHER_AUTHORED')
                """).param("id", skillId.toString()).param("key", "TEST-" + skillId).update();
        jdbcClient.sql("""
                INSERT INTO question(id, source_problem_key, prompt_text, answer_type, options_json,
                    correct_answer, difficulty, data_version, active, knowledge_model_mode, content_origin)
                VALUES (:id, :key, 'Seed question', 'SINGLE_CHOICE',
                    JSON_ARRAY(JSON_OBJECT('choiceId','A','label','A'),JSON_OBJECT('choiceId','B','label','B')),
                    'A', 0.5, 'lms-test', TRUE, 'ONLINE_BKT', 'TEACHER_AUTHORED')
                """).param("id", questionId.toString()).param("key", "TEST-" + questionId).update();
        jdbcClient.sql("INSERT INTO question_skill(question_id, skill_id, ordinal) VALUES (:questionId,:skillId,1)")
                .param("questionId", questionId.toString()).param("skillId", skillId.toString()).update();
        jdbcClient.sql("INSERT INTO course_question(course_id, question_id, active, ordinal) VALUES (:courseId,:questionId,TRUE,1)")
                .param("courseId", courseId.toString()).param("questionId", questionId.toString()).update();
        return courseId;
    }

    private JsonNode createAssessment(UUID courseId, OffsetDateTime dueAt) throws Exception {
        String skillId = jdbcClient.sql("""
                SELECT qs.skill_id FROM course_question cq JOIN question_skill qs ON qs.question_id=cq.question_id
                WHERE cq.course_id=:courseId LIMIT 1
                """).param("courseId", courseId.toString()).query(String.class).single();
        return json(mockMvc.perform(post("/api/v1/lms/teacher/courses/{courseId}/assessments", courseId)
                        .with(authentication(teacherAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Lifecycle assessment","description":"Lifecycle test","assessmentType":"QUIZ",
                                 "dueAt":"%s","questions":[{"prompt":"Choose A","options":[
                                 {"choiceId":"A","label":"A"},{"choiceId":"B","label":"B"}],
                                 "correctChoiceId":"A","points":2,"skillIds":["%s"]}]}
                                """.formatted(dueAt, skillId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions submit(String assessmentId, String questionId, String choice, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/lms/assessments/{assessmentId}/submissions", assessmentId)
                .with(authentication(studentAuth())).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":[{\"questionId\":\"" + questionId
                        + "\",\"selectedChoiceId\":\"" + choice + "\"}]}"));
    }

    private UsernamePasswordAuthenticationToken teacherAuth() {
        EduTwinPrincipal principal = new EduTwinPrincipal(
                TEACHER_ID, "lms-teacher", "LMS Teacher", "", UserRole.TEACHER, Set.of(), true);
        return new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());
    }

    private UsernamePasswordAuthenticationToken studentAuth() {
        EduTwinPrincipal principal = new EduTwinPrincipal(
                STUDENT_ID, "lms-student", "LMS Student", "", UserRole.STUDENT, Set.of(), true);
        return new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private long tokenVersion(UUID userId) {
        return jdbcClient.sql("SELECT token_version FROM user_account WHERE id = :id")
                .param("id", userId.toString())
                .query(Long.class)
                .single();
    }

    private void cleanup() {
        jdbcClient.sql("DELETE FROM lms_submission_answer").update();
        jdbcClient.sql("DELETE FROM lms_submission").update();
        jdbcClient.sql("DELETE FROM lms_attempt_request").update();
        jdbcClient.sql("DELETE FROM lms_lesson_progress").update();
        jdbcClient.sql("DELETE FROM lms_assessment_status_history").update();
        jdbcClient.sql("DELETE FROM lms_assessment_question_skill").update();
        jdbcClient.sql("DELETE FROM lms_assessment_question").update();
        jdbcClient.sql("DELETE FROM lms_assessment").update();
        jdbcClient.sql("DELETE FROM lms_content_status_history").update();
        jdbcClient.sql("DELETE FROM lms_lesson").update();
        jdbcClient.sql("DELETE FROM lms_section").update();
        jdbcClient.sql("DELETE FROM lms_enrollment_history").update();
        jdbcClient.sql("DELETE FROM course_enrollment").update();
        jdbcClient.sql("DELETE FROM answer_event_skill").update();
        jdbcClient.sql("DELETE FROM answer_event").update();
        jdbcClient.sql("DELETE FROM course_question").update();
        jdbcClient.sql("DELETE FROM question_skill").update();
        jdbcClient.sql("DELETE FROM question").update();
        jdbcClient.sql("DELETE FROM knowledge_skill").update();
        jdbcClient.sql("DELETE FROM teaching_assignment").update();
        jdbcClient.sql("DELETE FROM lms_course_status_history").update();
        jdbcClient.sql("DELETE FROM course").update();
        jdbcClient.sql("DELETE FROM student_profile").update();
        jdbcClient.sql("DELETE FROM teacher_profile").update();
        jdbcClient.sql("DELETE FROM user_role").update();
        jdbcClient.sql("DELETE FROM user_account").update();
        jdbcClient.sql("DELETE FROM role_definition").update();
    }
}
