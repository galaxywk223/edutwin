package com.edutwin.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edutwin.api.model.UserRole;
import com.edutwin.analysis.AnalysisWorkflowStore;
import com.edutwin.analysis.AnalysisWorkflowStore.Failure;
import com.edutwin.analysis.AnalysisWorkflowStore.LeaseDecision;
import com.edutwin.identity.EduTwinPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=${EDUTWIN_DB_URL:"
            + "jdbc:mysql://127.0.0.1:33306/edutwin_test?useUnicode=true"
            + "&characterEncoding=utf8&serverTimezone=UTC"
            + "&allowPublicKeyRetrieval=true&useSSL=false}",
    "spring.datasource.username=${EDUTWIN_DB_USER:edutwin}",
    "spring.datasource.password=${EDUTWIN_DB_PASSWORD:edutwin-local}",
    "edutwin.ai.enabled=false"
})
@AutoConfigureMockMvc
@Import(AnswerSubmissionIntegrationTest.TestTaskExecutorConfiguration.class)
class AnswerSubmissionIntegrationTest {

    private static final UUID STUDENT_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_STUDENT_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID COURSE_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000010");
    private static final UUID QUESTION_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000020");
    private static final UUID SKILL_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000030");
    private static final UUID PROCESSING_RUN_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000040");
    private static final UUID ASSISTMENTS_SOURCE_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000041");
    private static final UUID OULAD_SOURCE_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000042");
    private static final UUID FUSION_SOURCE_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000043");
    private static final String ASSISTMENTS_VERSION = "answer-test-assistments-v1";
    private static final String OULAD_VERSION = "answer-test-oulad-v1";
    private static final String FUSION_VERSION = "answer-test-fusion-v1";
    private static final UUID CORRELATION_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000099");
    private static final String IDEMPOTENCY_KEY = "answer-key-0001";
    private static final List<ModelFixture> MODELS = List.of(
            new ModelFixture("MASTERY", "BKT", "answer-test-mastery-v1", ASSISTMENTS_VERSION, '1'),
            new ModelFixture("NEXT_CORRECT", "AKT", "answer-test-next-v1", ASSISTMENTS_VERSION, '2'),
            new ModelFixture("RISK", "CATBOOST", "answer-test-risk-v1", OULAD_VERSION, '3'),
            new ModelFixture("EXPLANATION", "SHAP", "answer-test-explainer-v1", OULAD_VERSION, '4'),
            new ModelFixture("PLAN_RULES", "RULE", "answer-test-planner-v1", FUSION_VERSION, '5'),
            new ModelFixture("DIAGNOSIS", "DEEPSEEK", "answer-test-diagnosis-v1", FUSION_VERSION, '6'));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnalysisWorkflowStore workflowStore;

    @BeforeEach
    void seedIndependentSubmissionGraph() {
        assertEquals(
                "edutwin_test",
                jdbcClient.sql("SELECT DATABASE()").query(String.class).single(),
                "integration tests require edutwin_test");
        cleanup();
        seedVersionCatalog();
        seedStudentCourseAndQuestion();
    }

    @AfterEach
    void removeIndependentSubmissionGraph() {
        cleanup();
    }

    @Test
    void acceptedAnswerCreatesAtomicTraceableFactsAndPreallocatedSnapshot() throws Exception {
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2).toString();
        MvcResult result = submit(STUDENT_ID, "A", occurredAt, IDEMPOTENCY_KEY)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.stage").value("QUEUED"))
                .andExpect(jsonPath("$.lastEventSequence").value(1))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String jobId = response.path("jobId").asText();
        var internalTrace = jdbcClient.sql("""
                        SELECT answer_event_id, target_snapshot_id, correlation_id,
                               JSON_LENGTH(data_versions) data_version_count,
                               JSON_LENGTH(requested_model_versions) requested_model_count,
                               JSON_LENGTH(effective_model_versions) effective_model_count
                        FROM analysis_job WHERE id = :jobId
                        """)
                .param("jobId", jobId)
                .query((resultSet, rowNumber) -> java.util.Map.of(
                        "answerEventId", resultSet.getString("answer_event_id"),
                        "snapshotId", resultSet.getString("target_snapshot_id"),
                        "correlationId", resultSet.getString("correlation_id"),
                        "dataVersionCount", resultSet.getInt("data_version_count"),
                        "requestedModelCount", resultSet.getInt("requested_model_count"),
                        "effectiveModelCount", resultSet.getInt("effective_model_count")))
                .single();
        String answerEventId = (String) internalTrace.get("answerEventId");
        String snapshotId = (String) internalTrace.get("snapshotId");
        assertEquals(CORRELATION_ID.toString(), internalTrace.get("correlationId"));
        assertEquals(3, internalTrace.get("dataVersionCount"));
        assertEquals(6, internalTrace.get("requestedModelCount"));
        assertEquals(6, internalTrace.get("effectiveModelCount"));
        assertEquals("/api/v1/analysis/jobs/" + jobId, result.getResponse().getHeader("Location"));

        assertEquals(1L, count("answer_event", "id", answerEventId));
        assertEquals(1L, count("analysis_job", "id", jobId));
        assertEquals(1L, count("outbox_event", "aggregate_id", jobId));
        assertEquals(1L, count("analysis_job_event", "analysis_job_id", jobId));
        assertEquals(1L, count("answer_event_skill", "answer_event_id", answerEventId));
        assertEquals(1L, count("idempotency_record", "analysis_job_id", jobId));

        String persistedSnapshot = jdbcClient.sql(
                        "SELECT target_snapshot_id FROM analysis_job WHERE id = :jobId")
                .param("jobId", jobId)
                .query(String.class)
                .single();
        assertEquals(snapshotId, persistedSnapshot);

        JsonNode event = objectMapper.readTree(jdbcClient.sql(
                        "SELECT payload FROM outbox_event WHERE aggregate_id = :jobId")
                .param("jobId", jobId)
                .query(String.class)
                .single());
        assertEquals("analysis.requested.v1", event.path("eventType").asText());
        assertEquals(1, event.path("eventVersion").asInt());
        assertEquals(jobId, event.path("aggregateId").asText());
        assertEquals(jobId, event.path("analysisJobId").asText());
        assertEquals(answerEventId, event.path("causationId").asText());
        assertEquals(snapshotId, event.path("snapshotId").asText());
        assertTrue(event.path("snapshotVersion").isNull());
        assertEquals(3, event.path("dataVersionIds").size());
        assertEquals(6, event.path("modelVersionIds").size());
        assertEquals("PUBLIC_API", event.path("payload").path("submissionSource").asText());
        assertEquals(1, event.path("payload").path("answerSequence").asInt());
        assertTrue(event.path("payload").path("answerCorrect").asBoolean());
        assertEquals(SKILL_ID.toString(),
                event.path("payload").path("knowledgeComponentIds").get(0).asText());

        var persistedProgress = jdbcClient.sql("""
                        SELECT sequence_no, event_type
                        FROM analysis_job_event
                        WHERE analysis_job_id = :jobId
                        """)
                .param("jobId", jobId)
                .query((resultSet, rowNumber) -> java.util.Map.of(
                        "sequence", resultSet.getLong("sequence_no"),
                        "eventType", resultSet.getString("event_type")))
                .single();
        assertEquals(1L, persistedProgress.get("sequence"));
        assertEquals("analysis.requested.v1", persistedProgress.get("eventType"));

        String persistedKey = jdbcClient.sql(
                        "SELECT idempotency_key FROM idempotency_record WHERE analysis_job_id = :jobId")
                .param("jobId", jobId)
                .query(String.class)
                .single();
        assertNotEquals(IDEMPOTENCY_KEY, persistedKey);
        assertEquals(64, persistedKey.length());
    }

    @Test
    void emptyAnswerHistoryReturnsAStablePage() throws Exception {
        mockMvc.perform(get("/api/v1/courses/{courseId}/answers", COURSE_ID)
                        .with(authentication(authenticationFor(STUDENT_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void answerHistoryReturnsReviewDetailsNewestFirstAndPaginatesAllSources() throws Exception {
        submit(
                        STUDENT_ID,
                        "A",
                        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(3).toString(),
                        "answer-key-history-1")
                .andExpect(status().isAccepted());
        jdbcClient.sql("""
                        UPDATE answer_event SET source_order_id = 990000001
                        WHERE course_id = :courseId AND student_id = :studentId
                          AND event_sequence = 1
                        """)
                .param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString())
                .update();
        submit(
                        STUDENT_ID,
                        "B",
                        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                        "answer-key-history-2")
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/courses/{courseId}/answers", COURSE_ID)
                        .with(authentication(authenticationFor(STUDENT_ID)))
                        .queryParam("page", "0")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].questionId").value(QUESTION_ID.toString()))
                .andExpect(jsonPath("$.items[0].prompt").value("Which choice is correct?"))
                .andExpect(jsonPath("$.items[0].selectedChoice.choiceId").value("B"))
                .andExpect(jsonPath("$.items[0].selectedChoice.label").value("Incorrect"))
                .andExpect(jsonPath("$.items[0].correctChoice.choiceId").value("A"))
                .andExpect(jsonPath("$.items[0].correctChoice.label").value("Correct"))
                .andExpect(jsonPath("$.items[0].correct").value(false))
                .andExpect(jsonPath("$.items[0].skills[0].skillId").value(SKILL_ID.toString()))
                .andExpect(jsonPath("$.items[0].skills[0].name").value("Answer Test Skill"))
                .andExpect(jsonPath("$.items[0].attemptNumber").value(2))
                .andExpect(jsonPath("$.items[0].eventSequence").value(2))
                .andExpect(jsonPath("$.items[0].sourceType").value("ONLINE"));

        mockMvc.perform(get("/api/v1/courses/{courseId}/answers", COURSE_ID)
                        .with(authentication(authenticationFor(STUDENT_ID)))
                        .queryParam("page", "1")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].selectedChoice.choiceId").value("A"))
                .andExpect(jsonPath("$.items[0].correct").value(true))
                .andExpect(jsonPath("$.items[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.items[0].eventSequence").value(1))
                .andExpect(jsonPath("$.items[0].sourceType").value("IMPORTED"));
    }

    @Test
    void answerHistoryRejectsInvalidPagingAndUnauthorizedRoles() throws Exception {
        mockMvc.perform(get("/api/v1/courses/{courseId}/answers", COURSE_ID)
                        .with(authentication(authenticationFor(STUDENT_ID)))
                        .queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("page"));

        mockMvc.perform(get("/api/v1/courses/{courseId}/answers", COURSE_ID)
                        .with(authentication(authenticationFor(OTHER_STUDENT_ID))))
                .andExpect(status().isForbidden());

        EduTwinPrincipal teacher = new EduTwinPrincipal(
                UUID.fromString("71000000-0000-0000-0000-000000000003"),
                "answer-history-teacher",
                "Answer History Teacher",
                "",
                UserRole.TEACHER,
                Set.of(COURSE_ID),
                true);
        Authentication teacherAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                teacher, "", teacher.getAuthorities());
        mockMvc.perform(get("/api/v1/courses/{courseId}/answers", COURSE_ID)
                        .with(authentication(teacherAuthentication)))
                .andExpect(status().isForbidden());
    }

    @Test
    void identicalReplayReturnsOriginalJobWithoutCreatingFacts() throws Exception {
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString();
        MvcResult first = submit(STUDENT_ID, "A", occurredAt, IDEMPOTENCY_KEY)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn();
        MvcResult replay = submit(STUDENT_ID, "A", occurredAt, IDEMPOTENCY_KEY)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertEquals(firstBody.path("jobId").asText(), replayBody.path("jobId").asText());
        assertEquals(firstBody.path("trace").path("answerEventId").asText(),
                replayBody.path("trace").path("answerEventId").asText());
        assertEquals(1L, courseStudentCount("answer_event"));
        assertEquals(1L, courseStudentCount("analysis_job"));
        assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM outbox_event")
                .query(Long.class).single());
        assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM idempotency_record")
                .query(Long.class).single());
    }

    @Test
    void reusedKeyWithDifferentCanonicalRequestReturnsConflict() throws Exception {
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString();
        submit(STUDENT_ID, "A", occurredAt, IDEMPOTENCY_KEY)
                .andExpect(status().isAccepted());

        submit(STUDENT_ID, "B", occurredAt, IDEMPOTENCY_KEY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(1L, courseStudentCount("answer_event"));
        assertEquals(1L, courseStudentCount("analysis_job"));
        assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM outbox_event")
                .query(Long.class).single());
    }

    @Test
    void concurrentIdenticalSubmissionsReturnOneWinningJob() throws Exception {
        String occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> request = () -> {
                ready.countDown();
                start.await();
                return submit(STUDENT_ID, "A", occurredAt, "answer-key-concurrent")
                        .andExpect(status().isAccepted())
                        .andReturn();
            };
            Future<MvcResult> first = executor.submit(request);
            Future<MvcResult> second = executor.submit(request);
            ready.await();
            start.countDown();

            MvcResult firstResult = first.get();
            MvcResult secondResult = second.get();
            JsonNode firstBody = objectMapper.readTree(firstResult.getResponse().getContentAsString());
            JsonNode secondBody = objectMapper.readTree(secondResult.getResponse().getContentAsString());
            assertEquals(firstBody.path("jobId").asText(), secondBody.path("jobId").asText());
            assertEquals(firstBody.path("trace").path("answerEventId").asText(),
                    secondBody.path("trace").path("answerEventId").asText());
            Set<String> replayHeaders = Set.of(
                    firstResult.getResponse().getHeader("Idempotency-Replayed"),
                    secondResult.getResponse().getHeader("Idempotency-Replayed"));
            assertEquals(Set.of("false", "true"), replayHeaders);
            assertEquals(1L, courseStudentCount("answer_event"));
            assertEquals(1L, courseStudentCount("analysis_job"));
            assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM outbox_event")
                    .query(Long.class).single());
            assertEquals(1L, jdbcClient.sql("SELECT COUNT(*) FROM idempotency_record")
                    .query(Long.class).single());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidChoiceRollsBackEverySubmissionFact() throws Exception {
        submit(
                        STUDENT_ID,
                        "not-a-choice",
                        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                        "answer-key-invalid")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CHOICE"));

        assertEquals(0L, courseStudentCount("answer_event"));
        assertEquals(0L, courseStudentCount("analysis_job"));
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM outbox_event")
                .query(Long.class).single());
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM idempotency_record")
                .query(Long.class).single());
    }

    @Test
    void studentClaimWithoutDatabaseEnrollmentIsForbidden() throws Exception {
        submit(
                        OTHER_STUDENT_ID,
                        "A",
                        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                        "answer-key-forbidden")
                .andExpect(status().isForbidden());

        assertEquals(0L, courseStudentCount("answer_event"));
        assertEquals(0L, courseStudentCount("analysis_job"));
    }

    @Test
    void teacherRoleCannotUseStudentAnswerEndpoint() throws Exception {
        EduTwinPrincipal teacher = new EduTwinPrincipal(
                UUID.fromString("71000000-0000-0000-0000-000000000003"),
                "answer-test-teacher",
                "Answer Test Teacher",
                "",
                UserRole.TEACHER,
                Set.of(COURSE_ID),
                true);
        Authentication teacherAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                teacher, "", teacher.getAuthorities());

        mockMvc.perform(post("/api/v1/courses/{courseId}/answers", COURSE_ID)
                        .with(authentication(teacherAuthentication))
                        .header("Idempotency-Key", "answer-key-teacher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(java.util.Map.of(
                                "questionId", QUESTION_ID.toString(),
                                "selectedChoiceId", "A",
                                "occurredAt", OffsetDateTime.now(ZoneOffset.UTC)
                                        .minusSeconds(1)
                                        .toString()))))
                .andExpect(status().isForbidden());

        assertEquals(0L, courseStudentCount("answer_event"));
        assertEquals(0L, courseStudentCount("analysis_job"));
    }

    @Test
    void terminalWorkerFailurePersistsStructuredJobAndOutboxFactsAtomically() throws Exception {
        MvcResult accepted = submit(
                        STUDENT_ID,
                        "A",
                        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                        "answer-key-worker-failure")
                .andExpect(status().isAccepted())
                .andReturn();
        UUID jobId = UUID.fromString(objectMapper
                .readTree(accepted.getResponse().getContentAsString())
                .path("jobId")
                .asText());

        var lease = workflowStore.acquire(jobId);
        assertEquals(LeaseDecision.ACQUIRED, lease.decision());
        assertEquals(1, lease.attemptCount());
        assertTrue(workflowStore.fail(
                jobId,
                new Failure(
                        "INVARIANT",
                        "ANALYSIS_INVARIANT_VIOLATION",
                        "A required persisted analysis invariant is not satisfied.",
                        false),
                lease.attemptCount()));

        var failed = jdbcClient.sql("""
                        SELECT status, stage, attempt_count, error_code, error_message,
                               error_retryable, completed_at, lease_owner, lease_expires_at
                        FROM analysis_job
                        WHERE id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNumber) -> java.util.Map.of(
                        "status", resultSet.getString("status"),
                        "stage", resultSet.getString("stage"),
                        "attempts", resultSet.getInt("attempt_count"),
                        "errorCode", resultSet.getString("error_code"),
                        "errorMessage", resultSet.getString("error_message"),
                        "retryable", resultSet.getBoolean("error_retryable"),
                        "completed", resultSet.getTimestamp("completed_at") != null,
                        "leaseCleared", resultSet.getString("lease_owner") == null
                                && resultSet.getTimestamp("lease_expires_at") == null))
                .single();
        assertEquals("FAILED", failed.get("status"));
        assertEquals("FAILED", failed.get("stage"));
        assertEquals(1, failed.get("attempts"));
        assertEquals("ANALYSIS_INVARIANT_VIOLATION", failed.get("errorCode"));
        assertEquals(false, failed.get("retryable"));
        assertEquals(true, failed.get("completed"));
        assertEquals(true, failed.get("leaseCleared"));

        List<String> events = jdbcClient.sql("""
                        SELECT event_type
                        FROM analysis_job_event
                        WHERE analysis_job_id = :jobId
                        ORDER BY sequence_no
                        """)
                .param("jobId", jobId.toString())
                .query(String.class)
                .list();
        assertEquals(List.of(
                "analysis.requested.v1", "analysis.started.v1", "analysis.failed.v1"), events);
        JsonNode failureEnvelope = objectMapper.readTree(jdbcClient.sql("""
                        SELECT payload
                        FROM outbox_event
                        WHERE aggregate_id = :jobId AND event_type = 'analysis.failed.v1'
                        """)
                .param("jobId", jobId.toString())
                .query(String.class)
                .single());
        assertEquals("FAILED", failureEnvelope.path("payload").path("status").asText());
        assertEquals(3, failureEnvelope.path("payload").path("terminalSseSequence").asInt());
        assertEquals("ANALYSIS_INVARIANT_VIOLATION",
                failureEnvelope.path("payload").path("errorCode").asText());
    }

    @Test
    void expiredProcessingLeaseResumesThePersistedStageWithoutDuplicatingStartedEvent() throws Exception {
        MvcResult accepted = submit(
                        STUDENT_ID,
                        "A",
                        OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                        "answer-key-worker-recovery")
                .andExpect(status().isAccepted())
                .andReturn();
        UUID jobId = UUID.fromString(objectMapper
                .readTree(accepted.getResponse().getContentAsString())
                .path("jobId")
                .asText());

        var firstLease = workflowStore.acquire(jobId);
        assertEquals(LeaseDecision.ACQUIRED, firstLease.decision());
        assertEquals("MODEL_INFERENCE", firstLease.stage());
        assertEquals(1, firstLease.attemptCount());
        jdbcClient.sql("""
                        UPDATE analysis_job
                        SET lease_expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 SECOND,
                            next_attempt_at = NULL
                        WHERE id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .update();

        var recoveredLease = workflowStore.acquire(jobId);
        assertEquals(LeaseDecision.ACQUIRED, recoveredLease.decision());
        assertEquals("MODEL_INFERENCE", recoveredLease.stage());
        assertEquals(2, recoveredLease.attemptCount());
        assertEquals(1L, jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM analysis_job_event
                        WHERE analysis_job_id = :jobId
                          AND event_type = 'analysis.started.v1'
                        """)
                .param("jobId", jobId.toString())
                .query(Long.class)
                .single());
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            UUID studentId, String choiceId, String occurredAt, String idempotencyKey)
            throws Exception {
        return mockMvc.perform(post("/api/v1/courses/{courseId}/answers", COURSE_ID)
                .with(authentication(authenticationFor(studentId)))
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-ID", CORRELATION_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(java.util.Map.of(
                        "questionId", QUESTION_ID.toString(),
                        "selectedChoiceId", choiceId,
                        "occurredAt", occurredAt))));
    }

    private Authentication authenticationFor(UUID studentId) {
        EduTwinPrincipal principal = new EduTwinPrincipal(
                studentId,
                "answer-test-" + studentId,
                "Answer Test Student",
                "",
                UserRole.STUDENT,
                Set.of(COURSE_ID),
                true);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, "", principal.getAuthorities());
    }

    private void seedVersionCatalog() {
        jdbcClient.sql("""
                        INSERT INTO processing_run(
                            id, pipeline_version, git_tree_sha256, config_sha256,
                            random_seed, status, started_at, completed_at, output_manifest_sha256)
                        VALUES (
                            :id, 'answer-test-pipeline-v1', :sha, :sha,
                            42, 'SUCCEEDED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), :sha)
                        """)
                .param("id", PROCESSING_RUN_ID.toString())
                .param("sha", sha('a'))
                .update();
        insertSource(
                ASSISTMENTS_SOURCE_ID,
                "ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED",
                "ASSISTments Skill Builder");
        insertSource(OULAD_SOURCE_ID, "OULAD", "Open University Learning Analytics Dataset");
        insertSource(FUSION_SOURCE_ID, "EDUTWIN_DEMO", "EduTwin Demo Fusion");
        insertDatasetVersion(ASSISTMENTS_VERSION, ASSISTMENTS_SOURCE_ID, 'b');
        insertDatasetVersion(OULAD_VERSION, OULAD_SOURCE_ID, 'c');
        insertDatasetVersion(FUSION_VERSION, FUSION_SOURCE_ID, 'd');
        MODELS.forEach(this::insertModel);
    }

    private void insertSource(UUID id, String sourceKey, String name) {
        jdbcClient.sql("""
                        INSERT INTO dataset_source(
                            id, source_key, name, official_url, license_name, license_url, citation_text)
                        VALUES (
                            :id, :sourceKey, :name, 'https://example.test/source',
                            'Test fixture license', 'https://example.test/license', 'Integration fixture')
                        """)
                .param("id", id.toString())
                .param("sourceKey", sourceKey)
                .param("name", name)
                .update();
    }

    private void insertDatasetVersion(String version, UUID sourceId, char hashCharacter) {
        jdbcClient.sql("""
                        INSERT INTO dataset_version(
                            version_id, source_id, source_sha256, schema_sha256,
                            processing_config_sha256, manifest_sha256, processing_run_id,
                            row_count, processed_at, manifest_json)
                        VALUES (
                            :version, :sourceId, :sha, :sha, :sha, :sha, :runId,
                            10, CURRENT_TIMESTAMP(6), JSON_OBJECT('fixture', TRUE))
                        """)
                .param("version", version)
                .param("sourceId", sourceId.toString())
                .param("sha", sha(hashCharacter))
                .param("runId", PROCESSING_RUN_ID.toString())
                .update();
    }

    private void insertModel(ModelFixture model) {
        jdbcClient.sql("""
                        INSERT INTO model_version(
                            version_id, model_family, task_name, dataset_version_id,
                            status, random_seed, config_json, feature_contract_sha256,
                            calibrator_type, calibrator_sha256, manifest_sha256,
                            selected, frozen_at, created_at)
                        VALUES (
                            :version, :family, :task, :datasetVersion,
                            'ACTIVE', 42, JSON_OBJECT('modelName', :modelName), :sha,
                            NULL, NULL, :sha, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                        """)
                .param("version", model.version())
                .param("family", model.family())
                .param("task", model.task())
                .param("datasetVersion", model.datasetVersion())
                .param("modelName", "fixture-" + model.family().toLowerCase())
                .param("sha", sha(model.hashCharacter()))
                .update();
        UUID artifactId = UUID.nameUUIDFromBytes(("artifact:" + model.version())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbcClient.sql("""
                        INSERT INTO model_artifact(
                            id, model_version_id, artifact_role, artifact_uri,
                            sha256, size_bytes, dependency_versions)
                        VALUES (
                            :id, :version, 'SERVING_MODEL', :uri,
                            :sha, 128, JSON_OBJECT('fixture', '1'))
                        """)
                .param("id", artifactId.toString())
                .param("version", model.version())
                .param("uri", "artifact://" + model.version())
                .param("sha", sha(model.hashCharacter()))
                .update();
        jdbcClient.sql("""
                        INSERT INTO model_deployment(
                            task_name, active_version_id, rollback_version_id,
                            deployed_at, deployed_by)
                        VALUES (:task, :version, NULL, CURRENT_TIMESTAMP(6), 'answer-integration-test')
                        """)
                .param("task", model.task())
                .param("version", model.version())
                .update();
    }

    private void seedStudentCourseAndQuestion() {
        jdbcClient.sql("""
                        INSERT IGNORE INTO role_definition(code, description)
                        VALUES ('STUDENT', 'Student'), ('TEACHER', 'Teacher')
                        """)
                .update();
        insertStudent(STUDENT_ID, "answer-submission-student", "answer-student-1");
        insertStudent(OTHER_STUDENT_ID, "answer-submission-other", "answer-student-2");
        jdbcClient.sql("""
                        INSERT INTO course(id, code, title, term_label, starts_on, data_version, credits)
                        VALUES (
                            :id, 'ANSWER-TEST-710', 'Answer Submission Test',
                            '2026J', '2026-01-01', :dataVersion, 3.0)
                        """)
                .param("id", COURSE_ID.toString())
                .param("dataVersion", FUSION_VERSION)
                .update();
        jdbcClient.sql("""
                        INSERT INTO course_enrollment(course_id, student_id, status)
                        VALUES (:courseId, :studentId, 'ACTIVE')
                        """)
                .param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString())
                .update();
        jdbcClient.sql("""
                        INSERT INTO knowledge_skill(id, source_skill_key, name, data_version)
                        VALUES (:id, 'answer-skill-1', 'Answer Test Skill', :dataVersion)
                        """)
                .param("id", SKILL_ID.toString())
                .param("dataVersion", ASSISTMENTS_VERSION)
                .update();
        jdbcClient.sql("""
                        INSERT INTO question(
                            id, source_problem_key, prompt_text, answer_type, options_json,
                            correct_answer, difficulty, data_version, active)
                        VALUES (
                            :id, 'answer-question-1', 'Which choice is correct?',
                            'SINGLE_CHOICE', JSON_ARRAY(
                                JSON_OBJECT('choiceId', 'A', 'label', 'Correct'),
                                JSON_OBJECT('choiceId', 'B', 'label', 'Incorrect')),
                            'A', 0.5, :dataVersion, TRUE)
                        """)
                .param("id", QUESTION_ID.toString())
                .param("dataVersion", ASSISTMENTS_VERSION)
                .update();
        jdbcClient.sql("""
                        INSERT INTO question_skill(question_id, skill_id, ordinal)
                        VALUES (:questionId, :skillId, 1)
                        """)
                .param("questionId", QUESTION_ID.toString())
                .param("skillId", SKILL_ID.toString())
                .update();
        jdbcClient.sql("""
                        INSERT INTO course_question(course_id, question_id, active, ordinal)
                        VALUES (:courseId, :questionId, TRUE, 1)
                        """)
                .param("courseId", COURSE_ID.toString())
                .param("questionId", QUESTION_ID.toString())
                .update();
    }

    private void insertStudent(UUID id, String username, String syntheticKey) {
        jdbcClient.sql("""
                        INSERT INTO user_account(
                            id, username, password_hash, display_name, enabled)
                        VALUES (:id, :username, 'unused-test-hash', :username, TRUE)
                        """)
                .param("id", id.toString())
                .param("username", username)
                .update();
        jdbcClient.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'STUDENT')")
                .param("id", id.toString())
                .update();
        jdbcClient.sql("""
                        INSERT INTO student_profile(user_id, synthetic, synthetic_key, split_name)
                        VALUES (:id, TRUE, :syntheticKey, 'test')
                        """)
                .param("id", id.toString())
                .param("syntheticKey", syntheticKey)
                .update();
    }

    private long courseStudentCount(String table) {
        assertTrue(Set.of("answer_event", "analysis_job").contains(table));
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table
                        + " WHERE course_id = :courseId AND student_id = :studentId")
                .param("courseId", COURSE_ID.toString())
                .param("studentId", STUDENT_ID.toString())
                .query(Long.class)
                .single();
    }

    private long count(String table, String column, String value) {
        assertTrue(Set.of(
                        "answer_event",
                        "analysis_job",
                        "outbox_event",
                        "analysis_job_event",
                        "answer_event_skill",
                        "idempotency_record")
                .contains(table));
        assertTrue(Set.of("id", "aggregate_id", "analysis_job_id", "answer_event_id")
                .contains(column));
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :value")
                .param("value", value)
                .query(Long.class)
                .single();
    }

    private void cleanup() {
        jdbcClient.sql("""
                        DELETE FROM idempotency_record
                        WHERE user_id = :studentId OR user_id = :otherStudentId
                        """)
                .param("studentId", STUDENT_ID.toString())
                .param("otherStudentId", OTHER_STUDENT_ID.toString())
                .update();
        jdbcClient.sql("""
                        DELETE FROM outbox_event
                        WHERE aggregate_id IN (
                            SELECT id FROM analysis_job
                            WHERE course_id = :courseId)
                        """)
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("""
                        DELETE FROM analysis_job_event
                        WHERE analysis_job_id IN (
                            SELECT id FROM analysis_job
                            WHERE course_id = :courseId)
                        """)
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("""
                        DELETE FROM answer_event_skill
                        WHERE answer_event_id IN (
                            SELECT id FROM answer_event
                            WHERE course_id = :courseId)
                        """)
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM analysis_job WHERE course_id = :courseId")
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM answer_event WHERE course_id = :courseId")
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM course_question WHERE course_id = :courseId")
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM question_skill WHERE question_id = :questionId")
                .param("questionId", QUESTION_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM question WHERE id = :questionId")
                .param("questionId", QUESTION_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM knowledge_skill WHERE id = :skillId")
                .param("skillId", SKILL_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM course_enrollment WHERE course_id = :courseId")
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("DELETE FROM course WHERE id = :courseId")
                .param("courseId", COURSE_ID.toString())
                .update();
        jdbcClient.sql("""
                        DELETE FROM student_profile
                        WHERE user_id = :studentId OR user_id = :otherStudentId
                        """)
                .param("studentId", STUDENT_ID.toString())
                .param("otherStudentId", OTHER_STUDENT_ID.toString())
                .update();
        jdbcClient.sql("""
                        DELETE FROM user_role
                        WHERE user_id = :studentId OR user_id = :otherStudentId
                        """)
                .param("studentId", STUDENT_ID.toString())
                .param("otherStudentId", OTHER_STUDENT_ID.toString())
                .update();
        jdbcClient.sql("""
                        DELETE FROM user_account
                        WHERE id = :studentId OR id = :otherStudentId
                        """)
                .param("studentId", STUDENT_ID.toString())
                .param("otherStudentId", OTHER_STUDENT_ID.toString())
                .update();
        for (ModelFixture model : MODELS) {
            jdbcClient.sql("DELETE FROM model_deployment WHERE task_name = :task")
                    .param("task", model.task())
                    .update();
            jdbcClient.sql("DELETE FROM model_artifact WHERE model_version_id = :version")
                    .param("version", model.version())
                    .update();
            jdbcClient.sql("DELETE FROM model_version WHERE version_id = :version")
                    .param("version", model.version())
                    .update();
        }
        for (String version : List.of(ASSISTMENTS_VERSION, OULAD_VERSION, FUSION_VERSION)) {
            jdbcClient.sql("DELETE FROM dataset_version WHERE version_id = :version")
                    .param("version", version)
                    .update();
        }
        for (UUID sourceId : List.of(ASSISTMENTS_SOURCE_ID, OULAD_SOURCE_ID, FUSION_SOURCE_ID)) {
            jdbcClient.sql("DELETE FROM dataset_source WHERE id = :id")
                    .param("id", sourceId.toString())
                    .update();
        }
        jdbcClient.sql("DELETE FROM processing_run WHERE id = :id")
                .param("id", PROCESSING_RUN_ID.toString())
                .update();
    }

    private static String sha(char character) {
        return String.valueOf(character).repeat(64);
    }

    private record ModelFixture(
            String task,
            String family,
            String version,
            String datasetVersion,
            char hashCharacter) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTaskExecutorConfiguration {

        @Bean
        @Primary
        TaskExecutor answerSubmissionTestTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }
}
