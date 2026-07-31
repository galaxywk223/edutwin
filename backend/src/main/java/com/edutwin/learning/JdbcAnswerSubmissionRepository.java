package com.edutwin.learning;

import com.edutwin.analysis.AnalysisJobMapper;
import com.edutwin.api.model.AnalysisJob;
import com.edutwin.provenance.ActiveVersionCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnswerSubmissionRepository {

    private static final String JOB_SELECT = """
            SELECT j.*,
                   COALESCE((
                       SELECT MAX(je.sequence_no)
                       FROM analysis_job_event je
                       WHERE je.analysis_job_id = j.id
                   ), 0) AS last_event_sequence
            FROM analysis_job j
            WHERE j.id = :jobId
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AnalysisJobMapper analysisJobMapper;

    public JdbcAnswerSubmissionRepository(
            JdbcClient jdbcClient, ObjectMapper objectMapper, AnalysisJobMapper analysisJobMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.analysisJobMapper = analysisJobMapper;
    }

    public boolean lockActiveEnrollment(UUID courseId, UUID studentId) {
        String sql = """
                SELECT e.status
                FROM course_enrollment e
                WHERE e.course_id = :courseId
                  AND e.student_id = :studentId
                  AND e.status = 'ACTIVE'
                FOR UPDATE
                """;
        return jdbcClient.sql(sql)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query(String.class)
                .optional()
                .isPresent();
    }

    public Optional<IdempotencyFact> findIdempotency(
            UUID userId, String resourcePath, String keyHash, boolean forUpdate) {
        String sql = """
                SELECT request_sha256, analysis_job_id
                FROM idempotency_record
                WHERE user_id = :userId
                  AND http_method = 'POST'
                  AND resource_path = :resourcePath
                  AND idempotency_key = :keyHash
                """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbcClient.sql(sql)
                .param("userId", userId.toString())
                .param("resourcePath", resourcePath)
                .param("keyHash", keyHash)
                .query((resultSet, rowNumber) -> new IdempotencyFact(
                        resultSet.getString("request_sha256"),
                        UUID.fromString(resultSet.getString("analysis_job_id"))))
                .optional();
    }

    public Optional<QuestionFact> findQuestion(UUID courseId, UUID questionId) {
        String sql = """
                SELECT q.id,
                       q.prompt_text,
                       q.answer_type,
                       q.options_json,
                       q.correct_answer,
                       q.data_version
                FROM course_question cq
                JOIN question q ON q.id = cq.question_id
                WHERE cq.course_id = :courseId
                  AND cq.question_id = :questionId
                  AND cq.active = TRUE
                  AND q.active = TRUE
                """;
        return jdbcClient.sql(sql)
                .param("courseId", courseId.toString())
                .param("questionId", questionId.toString())
                .query((resultSet, rowNumber) -> new QuestionFact(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("prompt_text"),
                        resultSet.getString("answer_type"),
                        resultSet.getString("options_json"),
                        resultSet.getString("correct_answer"),
                        resultSet.getString("data_version"),
                        findSkillIds(questionId)))
                .optional();
    }

    public Optional<QuestionFact> findNextPracticeQuestion(UUID courseId, UUID studentId) {
        String plannedSql = """
                SELECT q.id,
                       q.prompt_text,
                       q.answer_type,
                       q.options_json,
                       q.correct_answer,
                       q.data_version
                FROM learning_plan_current_pointer current_plan
                JOIN learning_plan plan ON plan.id = current_plan.learning_plan_id
                JOIN learning_plan_item item ON item.learning_plan_id = plan.id
                JOIN course_question cq
                  ON cq.course_id = plan.course_id AND cq.question_id = item.question_id
                JOIN question q ON q.id = item.question_id
                WHERE current_plan.course_id = :courseId
                  AND current_plan.student_id = :studentId
                  AND plan.status = 'ACTIVE'
                  AND item.status IN ('PENDING', 'IN_PROGRESS')
                  AND cq.active = TRUE
                  AND q.active = TRUE
                ORDER BY item.ordinal
                LIMIT 1
                """;
        Optional<QuestionFact> planned = queryQuestion(plannedSql, courseId, studentId);
        if (planned.isPresent()) {
            return planned;
        }

        String fallbackSql = """
                SELECT q.id,
                       q.prompt_text,
                       q.answer_type,
                       q.options_json,
                       q.correct_answer,
                       q.data_version
                FROM course_question cq
                JOIN question q ON q.id = cq.question_id
                WHERE cq.course_id = :courseId
                  AND cq.active = TRUE
                  AND q.active = TRUE
                ORDER BY
                  (SELECT COUNT(*) FROM answer_event ae
                   WHERE ae.course_id = cq.course_id AND ae.student_id = :studentId
                     AND ae.question_id = q.id),
                  (SELECT MAX(ae.occurred_at) FROM answer_event ae
                   WHERE ae.course_id = cq.course_id AND ae.student_id = :studentId
                     AND ae.question_id = q.id) IS NOT NULL,
                  (SELECT MAX(ae.occurred_at) FROM answer_event ae
                   WHERE ae.course_id = cq.course_id AND ae.student_id = :studentId
                     AND ae.question_id = q.id),
                  cq.ordinal
                LIMIT 1
                """;
        return queryQuestion(fallbackSql, courseId, studentId);
    }

    private Optional<QuestionFact> queryQuestion(String sql, UUID courseId, UUID studentId) {
        return jdbcClient.sql(sql)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNumber) -> {
                    UUID questionId = UUID.fromString(resultSet.getString("id"));
                    return new QuestionFact(
                            questionId,
                            resultSet.getString("prompt_text"),
                            resultSet.getString("answer_type"),
                            resultSet.getString("options_json"),
                            resultSet.getString("correct_answer"),
                            resultSet.getString("data_version"),
                            findSkillIds(questionId));
                })
                .optional();
    }

    private List<UUID> findSkillIds(UUID questionId) {
        return jdbcClient.sql("""
                        SELECT skill_id
                        FROM question_skill
                        WHERE question_id = :questionId
                        ORDER BY ordinal
                        """)
                .param("questionId", questionId.toString())
                .query((resultSet, rowNumber) -> UUID.fromString(resultSet.getString("skill_id")))
                .list();
    }

    public int countAnswerHistory(UUID courseId, UUID studentId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM answer_event
                        WHERE course_id = :courseId AND student_id = :studentId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query(Integer.class)
                .single();
    }

    public List<AnswerHistoryFact> findAnswerHistory(
            UUID courseId, UUID studentId, int page, int size) {
        return jdbcClient.sql("""
                        SELECT ae.id,
                               ae.question_id,
                               ae.submitted_answer,
                               ae.correct,
                               ae.response_time_ms,
                               ae.attempt_number,
                               ae.event_sequence,
                               ae.occurred_at,
                               ae.source_order_id,
                               q.prompt_text,
                               q.answer_type,
                               q.options_json,
                               q.correct_answer
                        FROM answer_event ae
                        JOIN question q ON q.id = ae.question_id
                        WHERE ae.course_id = :courseId AND ae.student_id = :studentId
                        ORDER BY ae.event_sequence DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((resultSet, rowNumber) -> new AnswerHistoryFact(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("question_id")),
                        resultSet.getString("prompt_text"),
                        resultSet.getString("answer_type"),
                        resultSet.getString("options_json"),
                        resultSet.getString("correct_answer"),
                        resultSet.getString("submitted_answer"),
                        resultSet.getBoolean("correct"),
                        resultSet.getLong("response_time_ms"),
                        resultSet.getInt("attempt_number"),
                        resultSet.getLong("event_sequence"),
                        OffsetDateTime.ofInstant(
                                resultSet.getTimestamp("occurred_at").toInstant(), ZoneOffset.UTC),
                        resultSet.getObject("source_order_id") != null))
                .list();
    }

    public Map<UUID, List<AnswerHistorySkillFact>> findAnswerHistorySkills(
            List<UUID> answerEventIds) {
        if (answerEventIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = answerEventIds.stream().map(UUID::toString).toList();
        List<AnswerHistorySkillFact> rows = jdbcClient.sql("""
                        SELECT aes.answer_event_id, aes.skill_id, ks.name
                        FROM answer_event_skill aes
                        JOIN knowledge_skill ks ON ks.id = aes.skill_id
                        WHERE aes.answer_event_id IN (:answerEventIds)
                        ORDER BY aes.answer_event_id, aes.ordinal
                        """)
                .param("answerEventIds", ids)
                .query((resultSet, rowNumber) -> new AnswerHistorySkillFact(
                        UUID.fromString(resultSet.getString("answer_event_id")),
                        UUID.fromString(resultSet.getString("skill_id")),
                        resultSet.getString("name")))
                .list();
        Map<UUID, List<AnswerHistorySkillFact>> result = new LinkedHashMap<>();
        for (AnswerHistorySkillFact row : rows) {
            result.computeIfAbsent(row.answerEventId(), ignored -> new ArrayList<>()).add(row);
        }
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    public Optional<AnalysisJob> findCurrentSnapshotJob(UUID courseId, UUID studentId) {
        String sql = """
                SELECT j.*,
                       COALESCE((
                           SELECT MAX(je.sequence_no)
                           FROM analysis_job_event je
                           WHERE je.analysis_job_id = j.id
                       ), 0) AS last_event_sequence
                FROM twin_current_pointer current_twin
                JOIN twin_snapshot snapshot ON snapshot.id = current_twin.snapshot_id
                JOIN analysis_job j ON j.id = snapshot.analysis_job_id
                WHERE current_twin.course_id = :courseId
                  AND current_twin.student_id = :studentId
                """;
        return jdbcClient.sql(sql)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query(analysisJobMapper)
                .optional();
    }

    public long nextEventSequence(UUID courseId, UUID studentId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(MAX(event_sequence), 0) + 1 AS next_sequence
                        FROM answer_event
                        WHERE course_id = :courseId AND student_id = :studentId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNumber) -> resultSet.getLong("next_sequence"))
                .single();
    }

    public int nextAttemptNumber(UUID courseId, UUID studentId, UUID questionId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) + 1 AS next_attempt
                        FROM answer_event
                        WHERE course_id = :courseId
                          AND student_id = :studentId
                          AND question_id = :questionId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("questionId", questionId.toString())
                .query((resultSet, rowNumber) -> resultSet.getInt("next_attempt"))
                .single();
    }

    public void insertAnswerEvent(
            UUID answerEventId,
            UUID courseId,
            UUID studentId,
            QuestionFact question,
            String selectedChoiceId,
            boolean correct,
            long responseTimeMs,
            int attemptNumber,
            long eventSequence,
            Instant occurredAt) {
        jdbcClient.sql("""
                        INSERT INTO answer_event(
                            id, course_id, student_id, question_id, source_order_id,
                            submitted_answer, correct, response_time_ms, attempt_number,
                            event_sequence, occurred_at, data_version)
                        VALUES (
                            :id, :courseId, :studentId, :questionId, NULL,
                            :selectedChoiceId, :correct, :responseTimeMs, :attemptNumber,
                            :eventSequence, :occurredAt, :dataVersion)
                        """)
                .param("id", answerEventId.toString())
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("questionId", question.id().toString())
                .param("selectedChoiceId", selectedChoiceId)
                .param("correct", correct)
                .param("responseTimeMs", responseTimeMs)
                .param("attemptNumber", attemptNumber)
                .param("eventSequence", eventSequence)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("dataVersion", question.dataVersion())
                .update();

        for (int index = 0; index < question.skillIds().size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO answer_event_skill(answer_event_id, skill_id, ordinal)
                            VALUES (:answerEventId, :skillId, :ordinal)
                            """)
                    .param("answerEventId", answerEventId.toString())
                    .param("skillId", question.skillIds().get(index).toString())
                    .param("ordinal", index + 1)
                    .update();
        }
    }

    public void insertAnalysisJob(
            UUID jobId,
            UUID answerEventId,
            UUID courseId,
            UUID studentId,
            UUID snapshotId,
            UUID correlationId,
            ActiveVersionCatalog.Catalog catalog,
            UUID aiConfigurationVersionId) {
        String dataVersions = json(catalog.dataVersions());
        String modelVersions = json(catalog.modelVersions());
        jdbcClient.sql("""
                        INSERT INTO analysis_job(
                            id, answer_event_id, course_id, student_id, target_snapshot_id,
                            correlation_id, status, stage, degraded, degraded_stages,
                            attempt_count, data_versions, requested_model_versions,
                            effective_model_versions, ai_configuration_version_id, version)
                        VALUES (
                            :id, :answerEventId, :courseId, :studentId, :snapshotId,
                            :correlationId, 'QUEUED', 'QUEUED', FALSE, JSON_ARRAY(),
                            0, :dataVersions, :modelVersions, :modelVersions,
                            :aiConfigurationVersionId, 1)
                        """)
                .param("id", jobId.toString())
                .param("answerEventId", answerEventId.toString())
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("snapshotId", snapshotId.toString())
                .param("correlationId", correlationId.toString())
                .param("dataVersions", dataVersions)
                .param("modelVersions", modelVersions)
                .param("aiConfigurationVersionId",
                        aiConfigurationVersionId == null ? null : aiConfigurationVersionId.toString())
                .update();
    }

    public void insertJobEvent(UUID eventId, UUID jobId, String payload) {
        jdbcClient.sql("""
                        INSERT INTO analysis_job_event(
                            id, analysis_job_id, sequence_no, event_type, payload)
                        VALUES (:id, :jobId, 1, 'analysis.requested.v1', :payload)
                        """)
                .param("id", eventId.toString())
                .param("jobId", jobId.toString())
                .param("payload", payload)
                .update();
    }

    public void insertOutbox(UUID eventId, UUID jobId, String payload) {
        jdbcClient.sql("""
                        INSERT INTO outbox_event(
                            id, aggregate_type, aggregate_id, event_type,
                            schema_version, payload, status, attempts)
                        VALUES (
                            :id, 'ANALYSIS_JOB', :jobId, 'analysis.requested.v1',
                            '1', :payload, 'PENDING', 0)
                        """)
                .param("id", eventId.toString())
                .param("jobId", jobId.toString())
                .param("payload", payload)
                .update();
    }

    public void insertIdempotency(
            UUID id,
            UUID userId,
            String resourcePath,
            String keyHash,
            String requestHash,
            UUID jobId,
            Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO idempotency_record(
                            id, user_id, http_method, resource_path, idempotency_key,
                            request_sha256, analysis_job_id, expires_at)
                        VALUES (
                            :id, :userId, 'POST', :resourcePath, :keyHash,
                            :requestHash, :jobId, :expiresAt)
                        """)
                .param("id", id.toString())
                .param("userId", userId.toString())
                .param("resourcePath", resourcePath)
                .param("keyHash", keyHash)
                .param("requestHash", requestHash)
                .param("jobId", jobId.toString())
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    public AnalysisJob getJob(UUID jobId) {
        return findJob(jobId).orElseThrow(() -> new IllegalStateException(
                "Committed analysis job " + jobId + " cannot be read back."));
    }

    public Optional<AnalysisJob> findJob(UUID jobId) {
        return jdbcClient.sql(JOB_SELECT)
                .param("jobId", jobId.toString())
                .query(analysisJobMapper)
                .optional();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("A versioned database payload could not be serialized.", exception);
        }
    }

    public record IdempotencyFact(String requestSha256, UUID analysisJobId) {}

    public record QuestionFact(
            UUID id,
            String prompt,
            String answerType,
            String optionsJson,
            String correctAnswer,
            String dataVersion,
            List<UUID> skillIds) {

        public QuestionFact {
            skillIds = List.copyOf(skillIds);
        }
    }

    public record AnswerHistoryFact(
            UUID answerEventId,
            UUID questionId,
            String prompt,
            String answerType,
            String optionsJson,
            String correctAnswer,
            String selectedAnswer,
            boolean correct,
            long responseTimeMs,
            int attemptNumber,
            long eventSequence,
            OffsetDateTime occurredAt,
            boolean imported) {}

    public record AnswerHistorySkillFact(UUID answerEventId, UUID skillId, String name) {}
}
