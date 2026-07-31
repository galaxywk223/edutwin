package com.edutwin.analysis;

import com.edutwin.api.model.AnalysisJob;
import com.edutwin.shared.web.DomainException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisWorkItemRepository {

    private final JdbcClient jdbcClient;
    private final AnalysisJobRepository jobRepository;

    public AnalysisWorkItemRepository(JdbcClient jdbcClient, AnalysisJobRepository jobRepository) {
        this.jdbcClient = jdbcClient;
        this.jobRepository = jobRepository;
    }

    public WorkItem load(UUID jobId) {
        AnalysisJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND,
                        "ANALYSIS_JOB_NOT_FOUND",
                        "The requested analysis job does not exist."));

        Answer answer = jdbcClient.sql("""
                        SELECT ae.id, ae.question_id, ae.correct, ae.response_time_ms,
                               ae.event_sequence, ae.occurred_at, ae.data_version,
                               q.knowledge_model_mode
                        FROM analysis_job aj
                        JOIN answer_event ae ON ae.id = aj.answer_event_id
                        JOIN question q ON q.id = ae.question_id
                        WHERE aj.id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNumber) -> new Answer(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("question_id")),
                        resultSet.getBoolean("correct"),
                        resultSet.getLong("response_time_ms"),
                        resultSet.getLong("event_sequence"),
                        offset(resultSet.getTimestamp("occurred_at")),
                        resultSet.getString("data_version"),
                        resultSet.getString("knowledge_model_mode")))
                .single();

        List<UUID> targetSkills = jdbcClient.sql("""
                        SELECT skill_id
                        FROM answer_event_skill
                        WHERE answer_event_id = :answerEventId
                        ORDER BY ordinal
                        """)
                .param("answerEventId", answer.id().toString())
                .query((resultSet, rowNumber) -> UUID.fromString(resultSet.getString("skill_id")))
                .list();
        if (targetSkills.isEmpty()) {
            throw new IllegalStateException("The answer event has no persisted skill associations.");
        }

        Optional<CurrentTwin> currentTwin = findCurrentTwin(job.getCourseId(), job.getStudentId());
        Map<UUID, SkillState> currentSkills = currentTwin
                .map(value -> findSkillStates(value.snapshotId()))
                .orElseGet(Map::of);
        List<Interaction> interactions = findInteractions(
                job.getCourseId(), job.getStudentId(), answer.eventSequence());
        if (interactions.isEmpty()
                || !interactions.get(interactions.size() - 1).eventId().equals(answer.id())) {
            throw new IllegalStateException("The target answer is not the final reconstructed interaction.");
        }
        List<CandidateQuestion> questions = findCandidateQuestions(job.getCourseId());
        if (questions.isEmpty()) {
            throw new IllegalStateException("The course has no active questions for plan generation.");
        }

        LocalDate courseStart = jdbcClient.sql("SELECT starts_on FROM course WHERE id = :courseId")
                .param("courseId", job.getCourseId().toString())
                .query(LocalDate.class)
                .single();

        UUID aiConfigurationVersionId = findAiConfigurationVersionId(jobId).orElse(null);

        return new WorkItem(
                job,
                aiConfigurationVersionId,
                answer,
                targetSkills,
                currentTwin.orElse(null),
                currentSkills,
                interactions,
                questions,
                riskFeatures(job.getCourseId(), job.getStudentId(), interactions, courseStart));
    }

    Optional<UUID> findAiConfigurationVersionId(UUID jobId) {
        return jdbcClient.sql("""
                        SELECT ai_configuration_version_id
                        FROM analysis_job
                        WHERE id = :jobId
                          AND ai_configuration_version_id IS NOT NULL
                        """)
                .param("jobId", jobId.toString())
                .query(UUID.class)
                .optional();
    }

    private Optional<CurrentTwin> findCurrentTwin(UUID courseId, UUID studentId) {
        return jdbcClient.sql("""
                        SELECT ts.id, ts.snapshot_version, ts.risk_probability,
                               ts.next_correct_probability, ts.engagement_score,
                               ts.persistence_score, ts.plan_completion_rate
                        FROM twin_current_pointer tcp
                        JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                        WHERE tcp.course_id = :courseId AND tcp.student_id = :studentId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNumber) -> new CurrentTwin(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getLong("snapshot_version"),
                        resultSet.getBigDecimal("risk_probability"),
                        resultSet.getBigDecimal("next_correct_probability"),
                        resultSet.getBigDecimal("engagement_score"),
                        resultSet.getBigDecimal("persistence_score"),
                        resultSet.getBigDecimal("plan_completion_rate")))
                .optional();
    }

    private Map<UUID, SkillState> findSkillStates(UUID snapshotId) {
        LinkedHashMap<UUID, SkillState> result = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT tss.skill_id, ks.name, tss.mastery_probability,
                               tss.next_correct_probability,
                               tss.mastery_model_version, tss.next_model_version
                        FROM twin_snapshot_skill tss
                        JOIN knowledge_skill ks ON ks.id = tss.skill_id
                        WHERE tss.snapshot_id = :snapshotId
                        ORDER BY ks.name, tss.skill_id
                        """)
                .param("snapshotId", snapshotId.toString())
                .query((resultSet, rowNumber) -> new SkillState(
                        UUID.fromString(resultSet.getString("skill_id")),
                        resultSet.getString("name"),
                        resultSet.getBigDecimal("mastery_probability"),
                        resultSet.getBigDecimal("next_correct_probability"),
                        resultSet.getString("mastery_model_version"),
                        resultSet.getString("next_model_version")))
                .list()
                .forEach(skill -> result.put(skill.skillId(), skill));
        return Collections.unmodifiableMap(result);
    }

    private List<Interaction> findInteractions(UUID courseId, UUID studentId, long throughSequence) {
        List<InteractionRow> rows = jdbcClient.sql("""
                        SELECT ae.id, ae.question_id, ae.correct, ae.response_time_ms,
                               ae.occurred_at, ae.event_sequence, ae.source_order_id,
                               aes.skill_id, aes.ordinal
                        FROM answer_event ae
                        JOIN answer_event_skill aes ON aes.answer_event_id = ae.id
                        WHERE ae.course_id = :courseId
                          AND ae.student_id = :studentId
                          AND ae.event_sequence <= :throughSequence
                        ORDER BY ae.event_sequence, aes.ordinal
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("throughSequence", throughSequence)
                .query((resultSet, rowNumber) -> new InteractionRow(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("question_id")),
                        resultSet.getBoolean("correct"),
                        resultSet.getLong("response_time_ms"),
                        offset(resultSet.getTimestamp("occurred_at")),
                        resultSet.getLong("event_sequence"),
                        resultSet.getObject("source_order_id") == null,
                        UUID.fromString(resultSet.getString("skill_id"))))
                .list();

        LinkedHashMap<UUID, MutableInteraction> grouped = new LinkedHashMap<>();
        for (InteractionRow row : rows) {
            MutableInteraction interaction = grouped.computeIfAbsent(
                    row.eventId(),
                    ignored -> new MutableInteraction(
                            row.eventId(),
                            row.questionId(),
                            row.correct(),
                            row.responseTimeMs(),
                            row.occurredAt(),
                            row.sequence(),
                            row.liveEvent(),
                            new LinkedHashSet<>()));
            interaction.skillIds().add(row.skillId());
        }
        return grouped.values().stream()
                .map(value -> new Interaction(
                        value.eventId(),
                        value.questionId(),
                        List.copyOf(value.skillIds()),
                        value.correct(),
                        value.responseTimeMs(),
                        value.occurredAt(),
                        value.sequence(),
                        value.liveEvent()))
                .toList();
    }

    private List<CandidateQuestion> findCandidateQuestions(UUID courseId) {
        return jdbcClient.sql("""
                        SELECT q.id AS question_id, qs.skill_id, ks.name AS skill_name,
                               q.prompt_text, q.difficulty, cq.ordinal
                        FROM course_question cq
                        JOIN question q ON q.id = cq.question_id
                        JOIN question_skill qs ON qs.question_id = q.id
                        JOIN knowledge_skill ks ON ks.id = qs.skill_id
                        WHERE cq.course_id = :courseId
                          AND cq.active = TRUE
                          AND q.active = TRUE
                        ORDER BY q.difficulty, cq.ordinal, qs.ordinal
                        """)
                .param("courseId", courseId.toString())
                .query((resultSet, rowNumber) -> new CandidateQuestion(
                        UUID.fromString(resultSet.getString("question_id")),
                        UUID.fromString(resultSet.getString("skill_id")),
                        resultSet.getString("skill_name"),
                        resultSet.getString("prompt_text"),
                        resultSet.getBigDecimal("difficulty")))
                .list();
    }

    private Map<String, Double> riskFeatures(
            UUID courseId, UUID studentId, List<Interaction> interactions, LocalDate courseStart) {
        LocalDate windowEnd = courseStart.plusDays(29);
        Map<String, Object> activity = jdbcClient.sql("""
                SELECT COUNT(*) AS interaction_count,
                       COALESCE(SUM(CASE WHEN event_type IN ('COURSE_ACCESS','LESSON_VIEW','VIDEO_PROGRESS',
                           'RESOURCE_VIEW','RESOURCE_DOWNLOAD','DISCUSSION_VIEW') THEN 1 ELSE 0 END), 0) AS total_clicks,
                       COUNT(DISTINCT DATE(occurred_at)) AS active_days,
                       COUNT(DISTINCT FLOOR(DATEDIFF(DATE(occurred_at), :courseStart) / 7)) AS active_weeks,
                       COUNT(DISTINCT CASE WHEN resource_id IS NOT NULL THEN resource_id END) AS resource_count,
                       COALESCE(SUM(CASE WHEN event_type IN ('ASSESSMENT_OPEN','ASSESSMENT_SUBMIT') THEN 1 ELSE 0 END), 0) AS assessment_count
                FROM learning_activity_event
                WHERE course_id = :courseId AND student_id = :studentId
                  AND occurred_at >= :courseStart AND occurred_at < :windowExclusive
                """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("courseStart", courseStart)
                .param("windowExclusive", windowEnd.plusDays(1))
                .query((rs, row) -> Map.<String, Object>of(
                        "interaction_count", rs.getLong("interaction_count"),
                        "total_clicks", rs.getLong("total_clicks"),
                        "active_days", rs.getLong("active_days"),
                        "active_weeks", rs.getLong("active_weeks"),
                        "resource_count", rs.getLong("resource_count"),
                        "assessment_count", rs.getLong("assessment_count")))
                .single();
        List<Interaction> sourceWindow = interactions.stream()
                .filter(value -> {
                    LocalDate day = value.occurredAt().toLocalDate();
                    return !value.liveEvent()
                            && !day.isBefore(courseStart)
                            && !day.isAfter(windowEnd);
                })
                .toList();
        List<Interaction> assessments = interactions.stream()
                .filter(value -> value.liveEvent() || sourceWindow.contains(value))
                .toList();
        long correct = assessments.stream().filter(Interaction::correct).count();
        long activeDays = sourceWindow.stream()
                .map(value -> value.occurredAt().toLocalDate())
                .distinct()
                .count();
        long activeWeeks = sourceWindow.stream()
                .map(value -> ChronoUnit.DAYS.between(
                        courseStart, value.occurredAt().toLocalDate()) / 7)
                .distinct()
                .count();
        long resourceCount = sourceWindow.stream()
                .map(Interaction::questionId)
                .distinct()
                .count();
        double meanScore = assessments.isEmpty()
                ? 0.0
                : 100.0 * correct / assessments.size();

        LinkedHashMap<String, Double> result = new LinkedHashMap<>();
        long rawInteractions = ((Number) activity.get("interaction_count")).longValue();
        result.put("vle_total_clicks", rawInteractions == 0 ? (double) sourceWindow.size() : ((Number) activity.get("total_clicks")).doubleValue());
        result.put("vle_interaction_count", rawInteractions == 0 ? (double) sourceWindow.size() : (double) rawInteractions);
        result.put("vle_active_days", rawInteractions == 0 ? (double) activeDays : ((Number) activity.get("active_days")).doubleValue());
        result.put("vle_active_weeks", rawInteractions == 0 ? (double) activeWeeks : ((Number) activity.get("active_weeks")).doubleValue());
        result.put("vle_resource_count", rawInteractions == 0 ? (double) resourceCount : ((Number) activity.get("resource_count")).doubleValue());
        result.put("assessment_count", rawInteractions == 0 ? (double) assessments.size() : ((Number) activity.get("assessment_count")).doubleValue());
        result.put("assessment_scored_count", (double) assessments.size());
        result.put("assessment_mean_score", meanScore);
        return Collections.unmodifiableMap(result);
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value.toInstant().atOffset(ZoneOffset.UTC);
    }

    public record WorkItem(
            AnalysisJob job,
            UUID aiConfigurationVersionId,
            Answer answer,
            List<UUID> targetSkillIds,
            CurrentTwin currentTwin,
            Map<UUID, SkillState> currentSkills,
            List<Interaction> interactions,
            List<CandidateQuestion> questions,
            Map<String, Double> riskFeatures) {

        public WorkItem {
            targetSkillIds = List.copyOf(targetSkillIds);
            currentSkills = Map.copyOf(currentSkills);
            interactions = List.copyOf(interactions);
            questions = List.copyOf(questions);
            riskFeatures = Map.copyOf(riskFeatures);
        }
    }

    public record Answer(
            UUID id,
            UUID questionId,
            boolean correct,
            long responseTimeMs,
            long eventSequence,
            OffsetDateTime occurredAt,
            String dataVersion,
            String knowledgeModelMode) {}

    public record CurrentTwin(
            UUID snapshotId,
            long snapshotVersion,
            BigDecimal riskProbability,
            BigDecimal nextCorrectProbability,
            BigDecimal engagementScore,
            BigDecimal persistenceScore,
            BigDecimal planCompletionRate) {}

    public record SkillState(
            UUID skillId,
            String skillName,
            BigDecimal masteryProbability,
            BigDecimal nextCorrectProbability,
            String masteryModelVersion,
            String nextModelVersion) {}

    public record Interaction(
            UUID eventId,
            UUID questionId,
            List<UUID> skillIds,
            boolean correct,
            long responseTimeMs,
            OffsetDateTime occurredAt,
            long sequence,
            boolean liveEvent) {}

    public record CandidateQuestion(
            UUID questionId,
            UUID skillId,
            String skillName,
            String prompt,
            BigDecimal difficulty) {}

    private record InteractionRow(
            UUID eventId,
            UUID questionId,
            boolean correct,
            long responseTimeMs,
            OffsetDateTime occurredAt,
            long sequence,
            boolean liveEvent,
            UUID skillId) {}

    private record MutableInteraction(
            UUID eventId,
            UUID questionId,
            boolean correct,
            long responseTimeMs,
            OffsetDateTime occurredAt,
            long sequence,
            boolean liveEvent,
            Set<UUID> skillIds) {}
}
