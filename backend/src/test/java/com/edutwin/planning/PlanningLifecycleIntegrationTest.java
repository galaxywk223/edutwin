package com.edutwin.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.edutwin.api.model.LearningPlan;
import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
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
class PlanningLifecycleIntegrationTest {
    private static final UUID STUDENT_ID = uuid("a2000000-0000-0000-0000-000000000001");
    private static final UUID COURSE_ID = uuid("a2000000-0000-0000-0000-000000000002");
    private static final UUID PLAN_ID = uuid("a2000000-0000-0000-0000-000000000003");
    private static final UUID TASK_ONE_ID = uuid("a2000000-0000-0000-0000-000000000004");
    private static final UUID TASK_TWO_ID = uuid("a2000000-0000-0000-0000-000000000005");

    @Autowired private JdbcClient jdbc;
    @Autowired private PlanningLifecycleService service;

    @BeforeEach
    void seedPlan() {
        jdbc.sql("""
                INSERT INTO role_definition(code, description) VALUES ('STUDENT', 'Student')
                ON DUPLICATE KEY UPDATE description = VALUES(description)
                """).update();
        jdbc.sql("""
                        INSERT INTO user_account(id, username, password_hash, display_name, enabled)
                        VALUES (:id, 'plan-student', 'not-used', 'Plan Student', TRUE)
                        """).param("id", STUDENT_ID.toString()).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'STUDENT')")
                .param("id", STUDENT_ID.toString()).update();
        jdbc.sql("""
                        INSERT INTO student_profile(user_id, synthetic, synthetic_key, split_name, student_number)
                        VALUES (:id, TRUE, 'plan-student-key', 'test', 'PLAN-STUDENT')
                        """).param("id", STUDENT_ID.toString()).update();
        jdbc.sql("""
                        INSERT INTO course(id, code, title, term_label, starts_on, data_version, status, credits)
                        VALUES (:id, 'PLAN-COURSE', 'Plan Course', '2026 Fall', '2026-09-01',
                            'plan-test', 'PUBLISHED', 3.0)
                        """).param("id", COURSE_ID.toString()).update();
        jdbc.sql("INSERT INTO course_enrollment(course_id, student_id, status) VALUES (:course, :student, 'ACTIVE')")
                .param("course", COURSE_ID.toString()).param("student", STUDENT_ID.toString()).update();
        seedAnalysisAndPlan();
    }

    @Test
    void taskStartSkipAndDeepLinkPreserveStablePlanTaskId() {
        PlanningDtos.PlanLifecycle initial = service.current(COURSE_ID, STUDENT_ID);
        assertEquals("Question one", initial.tasks().getFirst().questionTitle());
        assertEquals("Skill one", initial.tasks().getFirst().skillName());
        assertEquals(TASK_ONE_ID, initial.tasks().getFirst().planTaskId());

        PlanningDtos.PracticeTask practice = service.practiceTask(student(), COURSE_ID, TASK_ONE_ID);
        assertEquals(TASK_ONE_ID, practice.planTaskId());
        assertEquals("Skill one", practice.skillName());
        PlanningDtos.PlanLifecycle skipped = service.skipTask(
                student(), COURSE_ID, STUDENT_ID, TASK_TWO_ID,
                new PlanningDtos.SkipTaskRequest("Already mastered", PLAN_ID));
        assertEquals("SKIPPED", skipped.tasks().get(1).status());
        assertEquals("IN_PROGRESS", skipped.tasks().getFirst().status());
    }

    @Test
    void regenerationSupersedesCurrentPlanAndResetsTasks() {
        LearningPlan regenerated = service.regenerate(COURSE_ID, STUDENT_ID, "MANUAL_REFRESH");

        assertNotEquals(PLAN_ID, regenerated.getPlanId());
        assertEquals(2L, regenerated.getVersion());
        assertEquals("SUPERSEDED", jdbc.sql("SELECT status FROM learning_plan WHERE id = :id")
                .param("id", PLAN_ID.toString()).query(String.class).single());
        assertEquals(2, regenerated.getTasks().size());
    }

    @Test
    void expirationMovesActivePlanToExpiredLifecycle() {
        jdbc.sql("UPDATE learning_plan SET valid_until = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = :id")
                .param("id", PLAN_ID.toString()).update();
        service.expirePlans();
        assertEquals("EXPIRED", service.current(COURSE_ID, STUDENT_ID).status());
    }

    private void seedAnalysisAndPlan() {
        UUID skillOne = uuid("a2000000-0000-0000-0000-000000000010");
        UUID skillTwo = uuid("a2000000-0000-0000-0000-000000000011");
        UUID questionOne = uuid("a2000000-0000-0000-0000-000000000012");
        UUID questionTwo = uuid("a2000000-0000-0000-0000-000000000013");
        UUID answerId = uuid("a2000000-0000-0000-0000-000000000014");
        UUID jobId = uuid("a2000000-0000-0000-0000-000000000015");
        UUID snapshotId = uuid("a2000000-0000-0000-0000-000000000016");
        jdbc.sql("""
                        INSERT INTO knowledge_skill(id, source_skill_key, name, data_version) VALUES
                            (:skillOne, 'plan-skill-1', 'Skill one', 'plan-test'),
                            (:skillTwo, 'plan-skill-2', 'Skill two', 'plan-test')
                        """).param("skillOne", skillOne.toString()).param("skillTwo", skillTwo.toString()).update();
        question(questionOne, "plan-question-1", "Question one");
        question(questionTwo, "plan-question-2", "Question two");
        jdbc.sql("""
                        INSERT INTO answer_event(
                            id, course_id, student_id, question_id, submitted_answer, correct,
                            response_time_ms, attempt_number, event_sequence, occurred_at, data_version)
                        VALUES (:id, :courseId, :studentId, :questionId, 'A', TRUE,
                            100, 1, 1, CURRENT_TIMESTAMP(6), 'plan-test')
                        """).param("id", answerId.toString()).param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString()).param("questionId", questionOne.toString()).update();
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
                        INSERT INTO twin_snapshot(
                            id, course_id, student_id, snapshot_version, answer_event_id, analysis_job_id,
                            knowledge_mastery, next_correct_probability, risk_probability,
                            engagement_score, persistence_score, plan_completion_rate,
                            data_versions, model_versions, degraded)
                        VALUES (:id, :courseId, :studentId, 1, :answerId, :jobId,
                            JSON_OBJECT(), 0.5, 0.3, 0.5, 0.5, 0,
                            JSON_ARRAY(), JSON_ARRAY(), FALSE)
                        """).param("id", snapshotId.toString()).param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString()).param("answerId", answerId.toString())
                .param("jobId", jobId.toString()).update();
        jdbc.sql("""
                        INSERT INTO learning_plan(
                            id, course_id, student_id, plan_version, source_snapshot_id, source_job_id,
                            rule_version, status, valid_until, data_versions, model_versions)
                        VALUES (:id, :courseId, :studentId, 1, :snapshotId, :jobId,
                            'plan-rules-v1', 'ACTIVE', CURRENT_TIMESTAMP(6) + INTERVAL 7 DAY,
                            JSON_ARRAY(), JSON_ARRAY())
                        """).param("id", PLAN_ID.toString()).param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString()).param("snapshotId", snapshotId.toString())
                .param("jobId", jobId.toString()).update();
        planTask(TASK_ONE_ID, 1, questionOne, skillOne, "Question one");
        planTask(TASK_TWO_ID, 2, questionTwo, skillTwo, "Question two");
        jdbc.sql("""
                        INSERT INTO learning_plan_current_pointer(course_id, student_id, learning_plan_id)
                        VALUES (:courseId, :studentId, :planId)
                        """).param("courseId", COURSE_ID.toString()).param("studentId", STUDENT_ID.toString())
                .param("planId", PLAN_ID.toString()).update();
    }

    private void question(UUID id, String key, String title) {
        jdbc.sql("""
                        INSERT INTO question(
                            id, source_problem_key, prompt_text, answer_type, options_json,
                            correct_answer, difficulty, data_version, active)
                        VALUES (:id, :key, :title, 'SINGLE_CHOICE',
                            JSON_OBJECT('A', 'Yes', 'B', 'No'), 'A', 0.5, 'plan-test', TRUE)
                        """).param("id", id.toString()).param("key", key).param("title", title).update();
        jdbc.sql("INSERT INTO course_question(course_id, question_id, active, ordinal) VALUES (:course, :question, TRUE, :ordinal)")
                .param("course", COURSE_ID.toString()).param("question", id.toString())
                .param("ordinal", key.endsWith("1") ? 1 : 2).update();
    }

    private void planTask(UUID id, int ordinal, UUID questionId, UUID skillId, String title) {
        jdbc.sql("""
                        INSERT INTO learning_plan_item(
                            id, learning_plan_id, ordinal, question_id, skill_id, task_type, title,
                            rationale_code, target_mastery, target_count, completed_count, status, due_at)
                        VALUES (:id, :planId, :ordinal, :questionId, :skillId, 'PRACTICE', :title,
                            'LOW_MASTERY', 0.8, 2, 0, 'PENDING', CURRENT_TIMESTAMP(6) + INTERVAL 2 DAY)
                        """).param("id", id.toString()).param("planId", PLAN_ID.toString())
                .param("ordinal", ordinal).param("questionId", questionId.toString())
                .param("skillId", skillId.toString()).param("title", title).update();
    }

    private static EduTwinPrincipal student() {
        return new EduTwinPrincipal(
                STUDENT_ID, "plan-student", "Plan Student", "",
                UserRole.STUDENT, Set.of(COURSE_ID), true);
    }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
