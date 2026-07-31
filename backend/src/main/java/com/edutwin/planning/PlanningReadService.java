package com.edutwin.planning;

import com.edutwin.api.model.AnalysisJob;
import com.edutwin.api.model.DataVersionRef;
import com.edutwin.api.model.Diagnosis;
import com.edutwin.api.model.DiagnosisEvidence;
import com.edutwin.api.model.LearningPlan;
import com.edutwin.api.model.LearningPlanTask;
import com.edutwin.api.model.ModelVersionRef;
import com.edutwin.api.model.RiskBand;
import com.edutwin.api.model.TraceRef;
import com.edutwin.shared.web.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class PlanningReadService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PlanningReadService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public LearningPlan currentPlan(UUID courseId, UUID studentId) {
        UUID planId = jdbcClient.sql("""
                        SELECT learning_plan_id
                        FROM learning_plan_current_pointer
                        WHERE course_id = :courseId AND student_id = :studentId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNumber) ->
                        UUID.fromString(resultSet.getString("learning_plan_id")))
                .optional()
                .orElseThrow(() -> notFound("No current learning plan exists."));
        return plan(planId);
    }

    public LearningPlan planForSnapshot(UUID courseId, UUID studentId, UUID snapshotId) {
        UUID planId = jdbcClient.sql("""
                        SELECT id FROM learning_plan
                        WHERE course_id = :courseId AND student_id = :studentId
                          AND source_snapshot_id = :snapshotId
                        ORDER BY plan_version DESC
                        LIMIT 1
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("snapshotId", snapshotId.toString())
                .query((resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")))
                .optional()
                .orElseThrow(() -> notFound("No learning plan exists for the requested snapshot."));
        return plan(planId);
    }

    public LearningPlan planForCurrentSnapshot(UUID courseId, UUID studentId) {
        return planForSnapshot(courseId, studentId, currentSnapshotId(courseId, studentId));
    }

    public Diagnosis diagnosisForSnapshot(UUID courseId, UUID studentId, UUID snapshotId) {
        DiagnosisRow row = jdbcClient.sql("""
                        SELECT d.*, rp.risk_band
                        FROM diagnosis d
                        JOIN risk_prediction rp ON rp.analysis_job_id = d.analysis_job_id
                        WHERE d.course_id = :courseId AND d.student_id = :studentId
                          AND d.snapshot_id = :snapshotId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("snapshotId", snapshotId.toString())
                .query((resultSet, rowNumber) -> new DiagnosisRow(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("analysis_job_id")),
                        resultSet.getString("provider"),
                        resultSet.getString("effective_model"),
                        resultSet.getString("structured_content"),
                        resultSet.getBoolean("degraded"),
                        RiskBand.fromValue(resultSet.getString("risk_band")),
                        offset(resultSet.getTimestamp("created_at"))))
                .optional()
                .orElseThrow(() -> notFound("No diagnosis exists for the requested snapshot."));
        TraceRef trace = trace(row.jobId());
        JsonNode structured = read(row.structuredContent());
        LearningPlan plan = planForSnapshot(courseId, studentId, snapshotId);
        List<DiagnosisEvidence> evidence = jdbcClient.sql("""
                        SELECT rank_no, feature_name, raw_value, direction, contribution,
                               base_value, output_unit, risk_model_version
                        FROM diagnosis_evidence
                        WHERE diagnosis_id = :diagnosisId
                        ORDER BY rank_no
                        """)
                .param("diagnosisId", row.diagnosisId().toString())
                .query((resultSet, rowNumber) -> new DiagnosisEvidence(
                        resultSet.getInt("rank_no"),
                        resultSet.getString("feature_name"),
                        new BigDecimal(resultSet.getString("raw_value")),
                        DiagnosisEvidence.DirectionEnum.fromValue(resultSet.getString("direction")),
                        resultSet.getBigDecimal("contribution"),
                        DiagnosisEvidence.OutputUnitEnum.fromValue(resultSet.getString("output_unit")))
                        .trace(trace)
                        .baseValue(resultSet.getBigDecimal("base_value"))
                        .riskModelVersion(resultSet.getString("risk_model_version")))
                .list();
        return new Diagnosis(
                row.diagnosisId(),
                Diagnosis.SchemaVersionEnum._1_0,
                row.createdAt(),
                structured.path("summary").asText(),
                row.riskBand(),
                textArray(structured.path("strengths")),
                textArray(structured.path("concerns")),
                textArray(structured.path("recommendedActions")),
                evidence,
                plan)
                .trace(trace)
                .generationMode(row.degraded()
                        ? Diagnosis.GenerationModeEnum.TEMPLATE_FALLBACK
                        : row.provider().equals("DEEPSEEK")
                                ? Diagnosis.GenerationModeEnum.DEEPSEEK_TOOL_CALL
                                : Diagnosis.GenerationModeEnum.LLM_TOOL_CALL)
                .providerModel(row.effectiveModel())
                .toolCallVerified(structured.path("toolCallVerified").asBoolean(false));
    }

    public Diagnosis diagnosisForCurrentSnapshot(UUID courseId, UUID studentId) {
        return diagnosisForSnapshot(courseId, studentId, currentSnapshotId(courseId, studentId));
    }

    private UUID currentSnapshotId(UUID courseId, UUID studentId) {
        return jdbcClient.sql("""
                        SELECT snapshot_id FROM twin_current_pointer
                        WHERE course_id = :courseId AND student_id = :studentId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNumber) -> UUID.fromString(resultSet.getString("snapshot_id")))
                .optional()
                .orElseThrow(() -> notFound("No current twin snapshot exists."));
    }

    public LearningPlan planById(UUID planId) {
        return plan(planId);
    }

    private LearningPlan plan(UUID planId) {
        PlanRow row = jdbcClient.sql("""
                        SELECT lp.*, ts.analysis_job_id
                        FROM learning_plan lp
                        JOIN twin_snapshot ts ON ts.id = lp.source_snapshot_id
                        WHERE lp.id = :planId
                        """)
                .param("planId", planId.toString())
                .query((resultSet, rowNumber) -> new PlanRow(
                        UUID.fromString(resultSet.getString("id")),
                        UUID.fromString(resultSet.getString("student_id")),
                        UUID.fromString(resultSet.getString("course_id")),
                        resultSet.getLong("plan_version"),
                        UUID.fromString(resultSet.getString("source_snapshot_id")),
                        UUID.fromString(resultSet.getString("analysis_job_id")),
                        resultSet.getString("rule_version"),
                        LearningPlan.StatusEnum.fromValue(resultSet.getString("status")),
                        offset(resultSet.getTimestamp("created_at")),
                        offset(resultSet.getTimestamp("valid_until"))))
                .single();
        List<LearningPlanTask> tasks = jdbcClient.sql("""
                        SELECT * FROM learning_plan_item
                        WHERE learning_plan_id = :planId
                        ORDER BY ordinal
                        """)
                .param("planId", planId.toString())
                .query((resultSet, rowNumber) -> new LearningPlanTask(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getInt("ordinal"),
                        LearningPlanTask.TaskTypeEnum.fromValue(resultSet.getString("task_type")),
                        UUID.fromString(resultSet.getString("question_id")),
                        UUID.fromString(resultSet.getString("skill_id")),
                        resultSet.getBigDecimal("target_mastery"),
                        LearningPlanTask.ReasonCodeEnum.fromValue(
                                resultSet.getString("rationale_code")),
                        offset(resultSet.getTimestamp("due_at")),
                        LearningPlanTask.StatusEnum.fromValue(resultSet.getString("status"))))
                .list();
        BigDecimal completion = jdbcClient.sql("""
                        SELECT COALESCE(SUM(completed_count) / NULLIF(SUM(target_count), 0), 0)
                        FROM learning_plan_item WHERE learning_plan_id = :planId
                        """)
                .param("planId", planId.toString())
                .query(BigDecimal.class)
                .single();
        return new LearningPlan(
                row.planId(),
                row.studentId(),
                row.courseId(),
                row.version(),
                row.createdAt(),
                row.validUntil(),
                row.status(),
                completion,
                tasks)
                .trace(trace(row.jobId()))
                .basedOnSnapshotId(row.snapshotId())
                .ruleEngineVersion(row.ruleVersion());
    }

    private TraceRef trace(UUID jobId) {
        return jdbcClient.sql("""
                        SELECT correlation_id, answer_event_id, target_snapshot_id,
                               data_versions, requested_model_versions, effective_model_versions
                        FROM analysis_job WHERE id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNumber) -> new TraceRef(
                        UUID.fromString(resultSet.getString("correlation_id")),
                        UUID.fromString(resultSet.getString("answer_event_id")),
                        jobId,
                        UUID.fromString(resultSet.getString("target_snapshot_id")),
                        readSet(resultSet.getString("data_versions"), DataVersionRef.class),
                        readSet(resultSet.getString("requested_model_versions"), ModelVersionRef.class),
                        readSet(resultSet.getString("effective_model_versions"), ModelVersionRef.class)))
                .single();
    }

    private <T> Set<T> readSet(String json, Class<T> type) {
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(LinkedHashSet.class, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted trace JSON is invalid.", exception);
        }
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted diagnosis JSON is invalid.", exception);
        }
    }

    private static List<String> textArray(JsonNode node) {
        return node.isArray()
                ? java.util.stream.StreamSupport.stream(node.spliterator(), false)
                        .map(JsonNode::asText)
                        .filter(value -> !value.isBlank())
                        .toList()
                : List.of();
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, "PLANNING_ARTIFACT_NOT_FOUND", detail);
    }

    private record PlanRow(
            UUID planId,
            UUID studentId,
            UUID courseId,
            long version,
            UUID snapshotId,
            UUID jobId,
            String ruleVersion,
            LearningPlan.StatusEnum status,
            OffsetDateTime createdAt,
            OffsetDateTime validUntil) {}

    private record DiagnosisRow(
            UUID diagnosisId,
            UUID jobId,
            String provider,
            String effectiveModel,
            String structuredContent,
            boolean degraded,
            RiskBand riskBand,
            OffsetDateTime createdAt) {}
}
