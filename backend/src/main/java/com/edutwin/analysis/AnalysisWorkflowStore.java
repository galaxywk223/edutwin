package com.edutwin.analysis;

import com.edutwin.analysis.AnalysisInference.Evidence;
import com.edutwin.analysis.AnalysisInference.SkillPrediction;
import com.edutwin.analysis.AnalysisWorkItemRepository.SkillState;
import com.edutwin.analysis.AnalysisWorkItemRepository.WorkItem;
import com.edutwin.api.model.DataVersionRef;
import com.edutwin.api.model.ModelVersionRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AnalysisWorkflowStore {

    private static final BigDecimal INITIAL_MASTERY = new BigDecimal("0.20000000");
    private static final BigDecimal INITIAL_NEXT = new BigDecimal("0.30000000");
    private static final BigDecimal INITIAL_RISK = new BigDecimal("0.70000000");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final TypeReference<LinkedHashSet<String>> DEGRADATION_SET =
            new TypeReference<>() {};

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final LifecycleEventWriter eventWriter;
    private final TransactionTemplate transactionTemplate;
    private final String workerName;

    public AnalysisWorkflowStore(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            LifecycleEventWriter eventWriter,
            PlatformTransactionManager transactionManager,
            @Value("${edutwin.analysis.consumer-name:${HOSTNAME:local-backend}}") String workerName) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.eventWriter = eventWriter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.workerName = workerName;
    }

    public ProcessingLease acquire(UUID jobId) {
        ProcessingLease result = transactionTemplate.execute(status -> {
            StartState current = jdbcClient.sql("""
                            SELECT status, stage, attempt_count, lease_expires_at, next_attempt_at
                            FROM analysis_job
                            WHERE id = :jobId
                            FOR UPDATE
                            """)
                    .param("jobId", jobId.toString())
                    .query((resultSet, rowNumber) -> new StartState(
                            resultSet.getString("status"),
                            resultSet.getString("stage"),
                            resultSet.getInt("attempt_count"),
                            instant(resultSet.getTimestamp("lease_expires_at")),
                            instant(resultSet.getTimestamp("next_attempt_at"))))
                    .optional()
                    .orElseThrow(() -> new IllegalStateException(
                            "Analysis job " + jobId + " does not exist."));
            if ("COMPLETED".equals(current.status()) || "FAILED".equals(current.status())) {
                return new ProcessingLease(
                        LeaseDecision.TERMINAL, current.stage(), current.attemptCount());
            }

            Instant startedAt = Instant.now();
            if ("PROCESSING".equals(current.status())
                    && (isAfter(current.leaseExpiresAt(), startedAt)
                            || isAfter(current.nextAttemptAt(), startedAt))) {
                return new ProcessingLease(
                        LeaseDecision.DEFERRED, current.stage(), current.attemptCount());
            }
            if (!"QUEUED".equals(current.status()) && !"PROCESSING".equals(current.status())) {
                throw new IllegalStateException(
                        "Analysis job " + jobId + " has unsupported status " + current.status() + ".");
            }

            Instant leaseExpiresAt = startedAt.plus(LEASE_DURATION);
            int attempt = current.attemptCount() + 1;
            int updated = jdbcClient.sql("""
                            UPDATE analysis_job
                            SET status = 'PROCESSING',
                                stage = :stage,
                                attempt_count = :attempt,
                                lease_owner = :workerName,
                                lease_expires_at = :leaseExpiresAt,
                                next_attempt_at = NULL,
                                started_at = COALESCE(started_at, :startedAt),
                                version = version + 1
                            WHERE id = :jobId AND status = :currentStatus
                            """)
                    .param("stage", "QUEUED".equals(current.status())
                            ? "MODEL_INFERENCE"
                            : current.stage())
                    .param("attempt", attempt)
                    .param("workerName", workerName)
                    .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
                    .param("startedAt", Timestamp.from(startedAt))
                    .param("jobId", jobId.toString())
                    .param("currentStatus", current.status())
                    .update();
            if (updated != 1) {
                return new ProcessingLease(
                        LeaseDecision.DEFERRED, current.stage(), current.attemptCount());
            }

            String acquiredStage = "QUEUED".equals(current.status())
                    ? "MODEL_INFERENCE"
                    : current.stage();
            if ("QUEUED".equals(current.status())) {
                ObjectNode payload = objectMapper.createObjectNode();
                payload.put("startedAt", offset(startedAt));
                payload.put("leaseOwner", workerName);
                payload.put("leaseExpiresAt", offset(leaseExpiresAt));
                payload.put("processingAttempt", attempt);
                eventWriter.append(jobId, 2, "analysis.started.v1", payload, null, null, null);
            }
            return new ProcessingLease(LeaseDecision.ACQUIRED, acquiredStage, attempt);
        });
        if (result == null) {
            throw new IllegalStateException("The processing lease transaction returned no result.");
        }
        return result;
    }

    public void releaseForRetry(UUID jobId, int attemptCount) {
        long delaySeconds = Math.min(16L, 1L << Math.max(0, attemptCount - 1));
        Instant nextAttemptAt = Instant.now().plusSeconds(delaySeconds);
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        UPDATE analysis_job
                        SET lease_owner = NULL,
                            lease_expires_at = :nextAttemptAt,
                            next_attempt_at = :nextAttemptAt,
                            version = version + 1
                        WHERE id = :jobId
                          AND status = 'PROCESSING'
                          AND lease_owner = :workerName
                        """)
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .param("jobId", jobId.toString())
                .param("workerName", workerName)
                .update());
    }

    public boolean fail(UUID jobId, Failure failure, int processingAttempts) {
        Boolean failed = transactionTemplate.execute(status -> {
            FailureState current = jdbcClient.sql("""
                            SELECT status, stage
                            FROM analysis_job
                            WHERE id = :jobId
                            FOR UPDATE
                            """)
                    .param("jobId", jobId.toString())
                    .query((resultSet, rowNumber) -> new FailureState(
                            resultSet.getString("status"), resultSet.getString("stage")))
                    .optional()
                    .orElseThrow(() -> new IllegalStateException(
                            "Analysis job " + jobId + " does not exist."));
            if ("COMPLETED".equals(current.status()) || "FAILED".equals(current.status())) {
                return false;
            }
            if (!"PROCESSING".equals(current.status())) {
                throw new IllegalStateException(
                        "Analysis job " + jobId + " cannot fail from status " + current.status() + ".");
            }

            long sequence = jdbcClient.sql("""
                            SELECT COALESCE(MAX(sequence_no), 0) + 1
                            FROM analysis_job_event
                            WHERE analysis_job_id = :jobId
                            """)
                    .param("jobId", jobId.toString())
                    .query(Long.class)
                    .single();
            Instant failedAt = Instant.now();
            int updated = jdbcClient.sql("""
                            UPDATE analysis_job
                            SET status = 'FAILED',
                                stage = 'FAILED',
                                error_code = :errorCode,
                                error_message = :errorMessage,
                                error_retryable = :retryable,
                                completed_at = :failedAt,
                                lease_owner = NULL,
                                lease_expires_at = NULL,
                                next_attempt_at = NULL,
                                version = version + 1
                            WHERE id = :jobId
                              AND status = 'PROCESSING'
                              AND lease_owner = :workerName
                            """)
                    .param("errorCode", failure.errorCode())
                    .param("errorMessage", failure.message())
                    .param("retryable", failure.retryable())
                    .param("failedAt", Timestamp.from(failedAt))
                    .param("jobId", jobId.toString())
                    .param("workerName", workerName)
                    .update();
            if (updated != 1) {
                return false;
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("failedAt", offset(failedAt));
            payload.put("status", "FAILED");
            payload.put("failedStage", failure.failedStage());
            payload.put("errorCode", failure.errorCode());
            payload.put("retryable", failure.retryable());
            payload.put("processingAttempts", processingAttempts);
            payload.put("terminalSseSequence", sequence);
            eventWriter.append(
                    jobId, sequence, "analysis.failed.v1", payload, null, null, null);
            return true;
        });
        return Boolean.TRUE.equals(failed);
    }

    public void persistPredictions(WorkItem workItem, AnalysisInference inference) {
        transactionTemplate.executeWithoutResult(status -> {
            lockProcessingJob(workItem.job().getJobId(), "MODEL_INFERENCE");
            if (predictionExists(workItem.job().getJobId())) {
                return;
            }

            String knowledgeDataVersion = dataVersion(
                    workItem, DataVersionRef.SourceIdEnum.ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED);
            String riskDataVersion = dataVersion(workItem, DataVersionRef.SourceIdEnum.OULAD);
            boolean knowledgeDegraded = inference.degradedStages()
                    .contains("KNOWLEDGE_MODEL_UNAVAILABLE");
            boolean riskDegraded = inference.degradedStages()
                    .contains("RISK_MODEL_UNAVAILABLE");

            for (SkillPrediction prediction : inference.skills().values()) {
                jdbcClient.sql("""
                                INSERT INTO knowledge_prediction(
                                    id, analysis_job_id, answer_event_id, course_id, student_id,
                                    skill_id, mastery_probability, next_correct_probability,
                                    mastery_model_version, next_model_version, data_version, degraded)
                                VALUES (
                                    :id, :jobId, :answerEventId, :courseId, :studentId,
                                    :skillId, :mastery, :nextCorrect,
                                    :masteryModel, :nextModel, :dataVersion, :degraded)
                                """)
                        .param("id", UUID.randomUUID().toString())
                        .param("jobId", workItem.job().getJobId().toString())
                        .param("answerEventId", workItem.answer().id().toString())
                        .param("courseId", workItem.job().getCourseId().toString())
                        .param("studentId", workItem.job().getStudentId().toString())
                        .param("skillId", prediction.skillId().toString())
                        .param("mastery", prediction.masteryProbability())
                        .param("nextCorrect", prediction.nextCorrectProbability())
                        .param("masteryModel", prediction.masteryModelVersion())
                        .param("nextModel", prediction.nextModelVersion())
                        .param("dataVersion", knowledgeDataVersion)
                        .param("degraded", knowledgeDegraded)
                        .update();
            }

            jdbcClient.sql("""
                            INSERT INTO risk_prediction(
                                id, analysis_job_id, answer_event_id, course_id, student_id,
                                calibrated_probability, risk_band, base_value, feature_values,
                                model_version, data_version, calibrated, degraded)
                            VALUES (
                                :id, :jobId, :answerEventId, :courseId, :studentId,
                                :probability, :riskBand, :baseValue, :featureValues,
                                :modelVersion, :dataVersion, :calibrated, :degraded)
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("answerEventId", workItem.answer().id().toString())
                    .param("courseId", workItem.job().getCourseId().toString())
                    .param("studentId", workItem.job().getStudentId().toString())
                    .param("probability", inference.riskProbability())
                    .param("riskBand", inference.riskBand())
                    .param("baseValue", inference.riskBaseValue())
                    .param("featureValues", json(inference.riskFeatures()))
                    .param("modelVersion", modelVersion(
                            inference.effectiveModels(), ModelVersionRef.PurposeEnum.RISK))
                    .param("dataVersion", riskDataVersion)
                    .param("calibrated", inference.calibrated())
                    .param("degraded", riskDegraded)
                    .update();

            Set<String> mergedDegradations = mergeDegradationStages(
                    workItem.job().getJobId(), inference.degradedStages());
            int updated = jdbcClient.sql("""
                            UPDATE analysis_job
                            SET stage = 'SNAPSHOT_PERSISTENCE',
                                degraded = :degraded,
                                degraded_stages = :degradedStages,
                                effective_model_versions = :effectiveModels,
                                lease_expires_at = :leaseExpiresAt,
                                version = version + 1
                            WHERE id = :jobId
                              AND status = 'PROCESSING'
                              AND lease_owner = :workerName
                            """)
                    .param("degraded", !mergedDegradations.isEmpty())
                    .param("degradedStages", json(mergedDegradations))
                    .param("effectiveModels", json(inference.effectiveModels()))
                    .param("leaseExpiresAt", Timestamp.from(Instant.now().plus(LEASE_DURATION)))
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("workerName", workerName)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("The prediction transition lost its processing lease.");
            }

            ObjectNode payload = predictionPayload(workItem, inference);
            eventWriter.append(
                    workItem.job().getJobId(),
                    3,
                    "predictions.computed.v1",
                    payload,
                    null,
                    null,
                    null);
        });
    }

    public SnapshotResult persistSnapshot(WorkItem workItem, AnalysisInference inference) {
        SnapshotResult result = transactionTemplate.execute(status -> {
            lockProcessingJob(workItem.job().getJobId(), "SNAPSHOT_PERSISTENCE");
            Optional<SnapshotResult> existing = findSnapshotResult(workItem.job().getJobId());
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }

            PointerState pointer = lockCurrentPointer(
                    workItem.job().getCourseId(), workItem.job().getStudentId()).orElse(null);
            long previousSequence = pointer == null ? 0L : pointer.lastAppliedAnswerSequence();
            if (workItem.answer().eventSequence() != previousSequence + 1) {
                throw new IllegalStateException(
                        "Answer sequence " + workItem.answer().eventSequence()
                                + " cannot advance current sequence " + previousSequence + ".");
            }
            if (pointer != null
                    && workItem.currentTwin() != null
                    && !pointer.snapshotId().equals(workItem.currentTwin().snapshotId())) {
                throw new IllegalStateException("The current twin changed after inference input was loaded.");
            }

            BigDecimal planCompletion = applyPlanProgress(workItem);
            BigDecimal engagement = probability(workItem.interactions().size() / 24.0);
            int recentSize = Math.min(10, workItem.interactions().size());
            long recentCorrect = workItem.interactions()
                    .subList(workItem.interactions().size() - recentSize, workItem.interactions().size())
                    .stream()
                    .filter(AnalysisWorkItemRepository.Interaction::correct)
                    .count();
            BigDecimal persistence = probability(recentCorrect / (double) recentSize);
            long snapshotVersion = pointer == null ? 1L : pointer.snapshotVersion() + 1L;
            UUID snapshotId = workItem.job().getTrace().getSnapshotId();
            UUID previousSnapshotId = pointer == null ? null : pointer.snapshotId();
            Map<UUID, SkillState> mergedSkills = mergeSkillStates(workItem, inference);

            LinkedHashMap<String, BigDecimal> masteryJson = new LinkedHashMap<>();
            mergedSkills.values().stream()
                    .sorted(Comparator.comparing(value -> value.skillId().toString()))
                    .forEach(value -> masteryJson.put(
                            value.skillId().toString(), value.masteryProbability()));
            jdbcClient.sql("""
                            INSERT INTO twin_snapshot(
                                id, course_id, student_id, snapshot_version,
                                answer_event_id, analysis_job_id, knowledge_mastery,
                                next_correct_probability, risk_probability,
                                engagement_score, persistence_score, plan_completion_rate,
                                data_versions, model_versions, degraded)
                            VALUES (
                                :id, :courseId, :studentId, :snapshotVersion,
                                :answerEventId, :jobId, :knowledgeMastery,
                                :nextCorrect, :riskProbability,
                                :engagement, :persistence, :planCompletion,
                                :dataVersions, :modelVersions, :degraded)
                            """)
                    .param("id", snapshotId.toString())
                    .param("courseId", workItem.job().getCourseId().toString())
                    .param("studentId", workItem.job().getStudentId().toString())
                    .param("snapshotVersion", snapshotVersion)
                    .param("answerEventId", workItem.answer().id().toString())
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("knowledgeMastery", json(masteryJson))
                    .param("nextCorrect", inference.nextCorrectProbability())
                    .param("riskProbability", inference.riskProbability())
                    .param("engagement", engagement)
                    .param("persistence", persistence)
                    .param("planCompletion", planCompletion)
                    .param("dataVersions", json(workItem.job().getTrace().getDataVersions()))
                    .param("modelVersions", json(inference.effectiveModels()))
                    .param("degraded", inference.degraded())
                    .update();

            for (SkillState skill : mergedSkills.values()) {
                jdbcClient.sql("""
                                INSERT INTO twin_snapshot_skill(
                                    snapshot_id, skill_id, mastery_probability,
                                    next_correct_probability, mastery_model_version, next_model_version)
                                VALUES (
                                    :snapshotId, :skillId, :mastery,
                                    :nextCorrect, :masteryModel, :nextModel)
                                """)
                        .param("snapshotId", snapshotId.toString())
                        .param("skillId", skill.skillId().toString())
                        .param("mastery", skill.masteryProbability())
                        .param("nextCorrect", skill.nextCorrectProbability())
                        .param("masteryModel", skill.masteryModelVersion())
                        .param("nextModel", skill.nextModelVersion())
                        .update();
            }

            jdbcClient.sql("""
                            INSERT INTO twin_current_pointer(
                                course_id, student_id, snapshot_id, last_applied_answer_sequence)
                            VALUES (:courseId, :studentId, :snapshotId, :answerSequence)
                            ON DUPLICATE KEY UPDATE
                                snapshot_id = VALUES(snapshot_id),
                                last_applied_answer_sequence = VALUES(last_applied_answer_sequence)
                            """)
                    .param("courseId", workItem.job().getCourseId().toString())
                    .param("studentId", workItem.job().getStudentId().toString())
                    .param("snapshotId", snapshotId.toString())
                    .param("answerSequence", workItem.answer().eventSequence())
                    .update();

            int updated = jdbcClient.sql("""
                            UPDATE analysis_job
                            SET stage = 'PLAN_GENERATION',
                                lease_expires_at = :leaseExpiresAt,
                                version = version + 1
                            WHERE id = :jobId
                              AND status = 'PROCESSING'
                              AND lease_owner = :workerName
                            """)
                    .param("leaseExpiresAt", Timestamp.from(Instant.now().plus(LEASE_DURATION)))
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("workerName", workerName)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("The snapshot transition lost its processing lease.");
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("snapshotId", snapshotId.toString());
            payload.put("snapshotVersion", snapshotVersion);
            if (previousSnapshotId == null) {
                payload.putNull("previousSnapshotId");
            } else {
                payload.put("previousSnapshotId", previousSnapshotId.toString());
            }
            payload.put("createdAt", offset(Instant.now()));
            payload.put("immutable", true);
            ArrayNode changed = payload.putArray("changedFields");
            changed.add("mastery");
            changed.add("nextQuestionProbability");
            changed.add("riskProbability");
            if (workItem.currentTwin() == null
                    || engagement.compareTo(workItem.currentTwin().engagementScore()) != 0) {
                changed.add("engagement");
            }
            if (workItem.currentTwin() == null
                    || persistence.compareTo(workItem.currentTwin().persistenceScore()) != 0) {
                changed.add("persistence");
            }
            if (workItem.currentTwin() == null
                    || planCompletion.compareTo(workItem.currentTwin().planCompletionRate()) != 0) {
                changed.add("planCompletion");
            }
            eventWriter.append(
                    workItem.job().getJobId(),
                    4,
                    "twin.snapshot-created.v1",
                    payload,
                    snapshotVersion,
                    null,
                    null);
            return new SnapshotResult(
                    snapshotId,
                    snapshotVersion,
                    previousSnapshotId,
                    mergedSkills,
                    engagement,
                    persistence,
                    planCompletion);
        });
        if (result == null) {
            throw new IllegalStateException("The snapshot transaction returned no result.");
        }
        return result;
    }

    public PlanResult persistPlanAndDiagnosis(
            WorkItem workItem,
            AnalysisInference inference,
            SnapshotResult snapshot,
            DiagnosisContent diagnosisContent) {
        PlanResult result = transactionTemplate.execute(status -> {
            lockProcessingJob(workItem.job().getJobId(), "PLAN_GENERATION");
            Optional<PlanResult> existing = findPlanResult(workItem.job().getJobId());
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }

            PlanPointer previousPlan = lockCurrentPlan(
                    workItem.job().getCourseId(), workItem.job().getStudentId()).orElse(null);
            long planVersion = previousPlan == null ? 1L : previousPlan.version() + 1L;
            UUID planId = UUID.randomUUID();
            UUID diagnosisId = UUID.randomUUID();
            Instant now = Instant.now();
            Instant validUntil = now.plus(Duration.ofDays(7));

            if (previousPlan != null) {
                jdbcClient.sql("""
                                UPDATE learning_plan
                                SET status = 'SUPERSEDED', superseded_at = :supersededAt
                                WHERE id = :planId AND status = 'ACTIVE'
                                """)
                        .param("supersededAt", Timestamp.from(now))
                        .param("planId", previousPlan.planId().toString())
                        .update();
            }
            jdbcClient.sql("""
                            INSERT INTO learning_plan(
                                id, course_id, student_id, plan_version, source_snapshot_id,
                                source_job_id, rule_version, status, valid_until,
                                data_versions, model_versions)
                            VALUES (
                                :id, :courseId, :studentId, :planVersion, :snapshotId,
                                :jobId, 'planner-rules-v1', 'ACTIVE', :validUntil,
                                :dataVersions, :modelVersions)
                            """)
                    .param("id", planId.toString())
                    .param("courseId", workItem.job().getCourseId().toString())
                    .param("studentId", workItem.job().getStudentId().toString())
                    .param("planVersion", planVersion)
                    .param("snapshotId", snapshot.snapshotId().toString())
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("validUntil", Timestamp.from(validUntil))
                    .param("dataVersions", json(workItem.job().getTrace().getDataVersions()))
                    .param("modelVersions", json(inference.effectiveModels()))
                    .update();

            List<PlanTaskFact> tasks = selectPlanTasks(workItem, snapshot);
            for (int index = 0; index < tasks.size(); index++) {
                PlanTaskFact task = tasks.get(index);
                jdbcClient.sql("""
                                INSERT INTO learning_plan_item(
                                    id, learning_plan_id, ordinal, question_id, skill_id,
                                    task_type, title, rationale_code, target_mastery,
                                    target_count, completed_count, status, due_at)
                                VALUES (
                                    :id, :planId, :ordinal, :questionId, :skillId,
                                    :taskType, :title, :reasonCode, :targetMastery,
                                    :targetCount, 0, 'PENDING', :dueAt)
                                """)
                        .param("id", task.taskId().toString())
                        .param("planId", planId.toString())
                        .param("ordinal", index + 1)
                        .param("questionId", task.questionId().toString())
                        .param("skillId", task.skillId().toString())
                        .param("taskType", task.taskType())
                        .param("title", task.title())
                        .param("reasonCode", task.reasonCode())
                        .param("targetMastery", task.targetMastery())
                        .param("targetCount", task.targetCount())
                        .param("dueAt", Timestamp.from(now.plus(Duration.ofDays(index + 1L))))
                        .update();
            }

            jdbcClient.sql("""
                            INSERT INTO learning_plan_current_pointer(
                                course_id, student_id, learning_plan_id)
                            VALUES (:courseId, :studentId, :planId)
                            ON DUPLICATE KEY UPDATE learning_plan_id = VALUES(learning_plan_id)
                            """)
                    .param("courseId", workItem.job().getCourseId().toString())
                    .param("studentId", workItem.job().getStudentId().toString())
                    .param("planId", planId.toString())
                    .update();

            ObjectNode structured = objectMapper.createObjectNode();
            structured.put("summary", diagnosisContent.summary());
            structured.put("riskBand", inference.riskBand());
            structured.set("strengths", objectMapper.valueToTree(diagnosisContent.strengths()));
            structured.set("concerns", objectMapper.valueToTree(diagnosisContent.concerns()));
            structured.set(
                    "recommendedActions",
                    objectMapper.valueToTree(diagnosisContent.recommendedActions()));
            structured.put("toolCallVerified", diagnosisContent.toolCallVerified());

            jdbcClient.sql("""
                            INSERT INTO diagnosis(
                                id, course_id, student_id, snapshot_id, analysis_job_id,
                                schema_version, provider, requested_model, effective_model,
                                structured_content, degraded)
                            VALUES (
                                :id, :courseId, :studentId, :snapshotId, :jobId,
                                '1.0', :provider, :requestedModel, :effectiveModel,
                                :structuredContent, :degraded)
                            """)
                    .param("id", diagnosisId.toString())
                    .param("courseId", workItem.job().getCourseId().toString())
                    .param("studentId", workItem.job().getStudentId().toString())
                    .param("snapshotId", snapshot.snapshotId().toString())
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("provider", diagnosisContent.provider())
                    .param("requestedModel", diagnosisContent.requestedModel())
                    .param("effectiveModel", diagnosisContent.effectiveModel())
                    .param("structuredContent", json(structured))
                    .param("degraded", diagnosisContent.degraded())
                    .update();

            for (Evidence evidence : inference.evidence()) {
                jdbcClient.sql("""
                                INSERT INTO diagnosis_evidence(
                                    id, diagnosis_id, rank_no, feature_name, raw_value,
                                    direction, contribution, base_value, output_unit,
                                    risk_model_version)
                                VALUES (
                                    :id, :diagnosisId, :rankNo, :featureName, :rawValue,
                                    :direction, :contribution, :baseValue, :outputUnit,
                                    :riskModelVersion)
                                """)
                        .param("id", UUID.randomUUID().toString())
                        .param("diagnosisId", diagnosisId.toString())
                        .param("rankNo", evidence.rank())
                        .param("featureName", evidence.featureName())
                        .param("rawValue", json(evidence.rawValue()))
                        .param("direction", evidence.direction())
                        .param("contribution", evidence.contribution())
                        .param("baseValue", evidence.baseValue())
                        .param("outputUnit", evidence.outputUnit())
                        .param("riskModelVersion", evidence.riskModelVersion())
                        .update();
            }

            LinkedHashSet<String> degradedStages = new LinkedHashSet<>(
                    mergeDegradationStages(
                            workItem.job().getJobId(), inference.degradedStages()));
            if (diagnosisContent.degraded()) {
                degradedStages.add(diagnosisContent.degradationReason());
            }
            boolean degraded = !degradedStages.isEmpty();
            Set<ModelVersionRef> effectiveModels = replaceDiagnosisModel(
                    inference.effectiveModels(), diagnosisContent);

            int updated = jdbcClient.sql("""
                            UPDATE analysis_job
                            SET stage = 'EVENT_PUBLICATION',
                                degraded = :degraded,
                                degraded_stages = :degradedStages,
                                effective_model_versions = :effectiveModels,
                                lease_expires_at = :leaseExpiresAt,
                                version = version + 1
                            WHERE id = :jobId
                              AND status = 'PROCESSING'
                              AND lease_owner = :workerName
                            """)
                    .param("degraded", degraded)
                    .param("degradedStages", json(degradedStages))
                    .param("effectiveModels", json(effectiveModels))
                    .param("leaseExpiresAt", Timestamp.from(Instant.now().plus(LEASE_DURATION)))
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("workerName", workerName)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("The plan transition lost its processing lease.");
            }

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("learningPlanId", planId.toString());
            payload.put("learningPlanVersion", planVersion);
            if (previousPlan == null) {
                payload.putNull("previousLearningPlanId");
            } else {
                payload.put("previousLearningPlanId", previousPlan.planId().toString());
            }
            payload.put("createdAt", offset(now));
            payload.put("ruleEngineVersion", "planner-rules-v1");
            payload.put("itemCount", tasks.size());
            payload.put("diagnosisId", diagnosisId.toString());
            payload.put("degraded", degraded);
            payload.set("degradedStages", objectMapper.valueToTree(degradedStages));
            eventWriter.append(
                    workItem.job().getJobId(),
                    5,
                    "learning-plan.created.v1",
                    payload,
                    snapshot.snapshotVersion(),
                    planId,
                    planVersion);
            return new PlanResult(planId, planVersion, diagnosisId);
        });
        if (result == null) {
            throw new IllegalStateException("The plan transaction returned no result.");
        }
        return result;
    }

    public void complete(WorkItem workItem, SnapshotResult snapshot, PlanResult plan) {
        transactionTemplate.executeWithoutResult(status -> {
            lockProcessingJob(workItem.job().getJobId(), "EVENT_PUBLICATION");
            long facts = jdbcClient.sql("""
                            SELECT
                                (SELECT COUNT(*) FROM knowledge_prediction
                                 WHERE analysis_job_id = :jobId) AS knowledge_count,
                                (SELECT COUNT(*) FROM risk_prediction
                                 WHERE analysis_job_id = :jobId) AS risk_count,
                                (SELECT COUNT(*) FROM twin_snapshot
                                 WHERE analysis_job_id = :jobId AND id = :snapshotId) AS snapshot_count,
                                (SELECT COUNT(*) FROM learning_plan
                                 WHERE source_job_id = :jobId AND id = :planId) AS plan_count,
                                (SELECT COUNT(*) FROM diagnosis
                                 WHERE analysis_job_id = :jobId AND id = :diagnosisId) AS diagnosis_count
                            """)
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("snapshotId", snapshot.snapshotId().toString())
                    .param("planId", plan.planId().toString())
                    .param("diagnosisId", plan.diagnosisId().toString())
                    .query((resultSet, rowNumber) ->
                            (resultSet.getLong("knowledge_count") > 0
                                            && resultSet.getLong("risk_count") == 1
                                            && resultSet.getLong("snapshot_count") == 1
                                            && resultSet.getLong("plan_count") == 1
                                            && resultSet.getLong("diagnosis_count") == 1)
                                    ? 1L
                                    : 0L)
                    .single();
            if (facts != 1L) {
                throw new IllegalStateException("The job cannot complete because required facts are missing.");
            }

            Instant completedAt = Instant.now();
            int updated = jdbcClient.sql("""
                            UPDATE analysis_job
                            SET status = 'COMPLETED',
                                stage = 'COMPLETED',
                                completed_at = :completedAt,
                                lease_owner = NULL,
                                lease_expires_at = NULL,
                                version = version + 1
                            WHERE id = :jobId AND status = 'PROCESSING'
                              AND lease_owner = :workerName
                            """)
                    .param("completedAt", Timestamp.from(completedAt))
                    .param("jobId", workItem.job().getJobId().toString())
                    .param("workerName", workerName)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("The completion transition lost its processing lease.");
            }

            CompletionState completion = jdbcClient.sql("""
                            SELECT degraded, degraded_stages
                            FROM analysis_job
                            WHERE id = :jobId
                            """)
                    .param("jobId", workItem.job().getJobId().toString())
                    .query((resultSet, rowNumber) -> new CompletionState(
                            resultSet.getBoolean("degraded"),
                            resultSet.getString("degraded_stages")))
                    .single();
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("completedAt", offset(completedAt));
            payload.put("status", "COMPLETED");
            payload.put("degraded", completion.degraded());
            try {
                payload.set("degradedStages", objectMapper.readTree(completion.degradedStages()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Degraded stages are invalid JSON.", exception);
            }
            payload.put("terminalSseSequence", 6);
            payload.put("dashboardRefreshRequired", true);
            eventWriter.append(
                    workItem.job().getJobId(),
                    6,
                    "analysis.completed.v1",
                    payload,
                    snapshot.snapshotVersion(),
                    plan.planId(),
                    plan.planVersion());
        });
    }

    public AnalysisInference loadPersistedInference(WorkItem workItem) {
        LinkedHashMap<UUID, SkillPrediction> skills = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT skill_id, mastery_probability, next_correct_probability,
                               mastery_model_version, next_model_version
                        FROM knowledge_prediction
                        WHERE analysis_job_id = :jobId
                        ORDER BY skill_id
                        """)
                .param("jobId", workItem.job().getJobId().toString())
                .query((resultSet, rowNumber) -> new SkillPrediction(
                        UUID.fromString(resultSet.getString("skill_id")),
                        resultSet.getBigDecimal("mastery_probability"),
                        resultSet.getBigDecimal("next_correct_probability"),
                        resultSet.getString("mastery_model_version"),
                        resultSet.getString("next_model_version"),
                        resultSet.getString("mastery_model_version").startsWith("rule-")
                                ? "RULE_FALLBACK"
                                : "BKT"))
                .list()
                .forEach(value -> skills.put(value.skillId(), value));
        if (skills.isEmpty()) {
            throw new IllegalStateException("The persisted analysis contains no knowledge predictions.");
        }

        PersistedRisk risk = jdbcClient.sql("""
                        SELECT rp.calibrated_probability, rp.risk_band, rp.base_value,
                               rp.feature_values, rp.calibrated,
                               aj.degraded_stages, aj.effective_model_versions
                        FROM risk_prediction rp
                        JOIN analysis_job aj ON aj.id = rp.analysis_job_id
                        WHERE rp.analysis_job_id = :jobId
                        """)
                .param("jobId", workItem.job().getJobId().toString())
                .query((resultSet, rowNumber) -> new PersistedRisk(
                        resultSet.getBigDecimal("calibrated_probability"),
                        resultSet.getString("risk_band"),
                        resultSet.getBigDecimal("base_value"),
                        read(resultSet.getString("feature_values"), new TypeReference<Map<String, Double>>() {}),
                        resultSet.getBoolean("calibrated"),
                        read(resultSet.getString("degraded_stages"), new TypeReference<Set<String>>() {}),
                        read(resultSet.getString("effective_model_versions"),
                                new TypeReference<Set<ModelVersionRef>>() {})))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "The persisted analysis contains no risk prediction."));
        BigDecimal nextCorrect = skills.values().iterator().next().nextCorrectProbability();
        return new AnalysisInference(
                skills,
                nextCorrect,
                risk.probability(),
                risk.riskBand(),
                risk.baseValue(),
                risk.calibrated(),
                ModelInferenceService.KNOWLEDGE_CONTRACT,
                ModelInferenceService.RISK_CONTRACT,
                risk.features(),
                List.of(),
                risk.degradedStages(),
                risk.effectiveModels());
    }

    public void synchronizeRecoveredInference(UUID jobId, AnalysisInference inference) {
        transactionTemplate.executeWithoutResult(status -> {
            lockProcessingJob(jobId, null);
            Set<String> mergedDegradations = mergeDegradationStages(
                    jobId, inference.degradedStages());
            jdbcClient.sql("""
                            UPDATE analysis_job
                            SET degraded = :degraded,
                                degraded_stages = :degradedStages,
                                effective_model_versions = :effectiveModels,
                                lease_expires_at = :leaseExpiresAt,
                                version = version + 1
                            WHERE id = :jobId
                              AND status = 'PROCESSING'
                              AND lease_owner = :workerName
                            """)
                    .param("degraded", !mergedDegradations.isEmpty())
                    .param("degradedStages", json(mergedDegradations))
                    .param("effectiveModels", json(inference.effectiveModels()))
                    .param("leaseExpiresAt", Timestamp.from(Instant.now().plus(LEASE_DURATION)))
                    .param("jobId", jobId.toString())
                    .param("workerName", workerName)
                    .update();
        });
    }

    private Set<String> mergeDegradationStages(UUID jobId, Set<String> additions) {
        LinkedHashSet<String> result = jdbcClient.sql("""
                        SELECT degraded_stages FROM analysis_job WHERE id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNumber) -> {
                    String value = resultSet.getString("degraded_stages");
                    return value == null || value.isBlank()
                            ? new LinkedHashSet<String>()
                            : read(value, DEGRADATION_SET);
                })
                .single();
        result.addAll(additions);
        return Collections.unmodifiableSet(result);
    }

    public SnapshotResult loadSnapshotResult(UUID jobId) {
        return findSnapshotResult(jobId).orElseThrow(() -> new IllegalStateException(
                "Analysis job " + jobId + " contains no persisted twin snapshot."));
    }

    public PlanResult loadPlanResult(UUID jobId) {
        return findPlanResult(jobId).orElseThrow(() -> new IllegalStateException(
                "Analysis job " + jobId + " contains no persisted learning plan."));
    }

    private Optional<SnapshotResult> findSnapshotResult(UUID jobId) {
        Optional<SnapshotRow> row = jdbcClient.sql("""
                        SELECT ts.id, ts.snapshot_version, ts.engagement_score,
                               ts.persistence_score, ts.plan_completion_rate
                        FROM twin_snapshot ts
                        WHERE ts.analysis_job_id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNumber) -> new SnapshotRow(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getLong("snapshot_version"),
                        resultSet.getBigDecimal("engagement_score"),
                        resultSet.getBigDecimal("persistence_score"),
                        resultSet.getBigDecimal("plan_completion_rate")))
                .optional();
        return row.map(value -> new SnapshotResult(
                value.snapshotId(),
                value.snapshotVersion(),
                null,
                loadSnapshotSkills(value.snapshotId()),
                value.engagementScore(),
                value.persistenceScore(),
                value.planCompletionRate()));
    }

    private Optional<PointerState> lockCurrentPointer(UUID courseId, UUID studentId) {
        return jdbcClient.sql("""
                        SELECT tcp.snapshot_id, tcp.last_applied_answer_sequence,
                               ts.snapshot_version
                        FROM twin_current_pointer tcp
                        JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                        WHERE tcp.course_id = :courseId AND tcp.student_id = :studentId
                        FOR UPDATE
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNumber) -> new PointerState(
                        UUID.fromString(resultSet.getString("snapshot_id")),
                        resultSet.getLong("last_applied_answer_sequence"),
                        resultSet.getLong("snapshot_version")))
                .optional();
    }

    private BigDecimal applyPlanProgress(WorkItem workItem) {
        if (workItem.answer().correct()) {
            jdbcClient.sql("""
                            UPDATE learning_plan_item item
                            JOIN learning_plan_current_pointer pointer
                              ON pointer.learning_plan_id = item.learning_plan_id
                            JOIN learning_plan plan ON plan.id = item.learning_plan_id
                            SET item.completed_count = LEAST(item.target_count, item.completed_count + 1),
                                item.status = CASE
                                    WHEN item.completed_count + 1 >= item.target_count THEN 'COMPLETED'
                                    ELSE 'IN_PROGRESS'
                                END
                            WHERE pointer.course_id = :courseId
                              AND pointer.student_id = :studentId
                              AND plan.status = 'ACTIVE'
                              AND item.status IN ('PENDING', 'IN_PROGRESS')
                              AND item.question_id = :questionId
                            """)
                    .param("courseId", workItem.job().getCourseId().toString())
                    .param("studentId", workItem.job().getStudentId().toString())
                    .param("questionId", workItem.answer().questionId().toString())
                    .update();
        }
        BigDecimal completionRate = jdbcClient.sql("""
                        SELECT COALESCE(
                            SUM(item.completed_count) / NULLIF(SUM(item.target_count), 0),
                            0) AS completion_rate
                        FROM learning_plan_current_pointer pointer
                        JOIN learning_plan plan ON plan.id = pointer.learning_plan_id
                        JOIN learning_plan_item item ON item.learning_plan_id = plan.id
                        WHERE pointer.course_id = :courseId
                          AND pointer.student_id = :studentId
                          AND plan.status = 'ACTIVE'
                        """)
                .param("courseId", workItem.job().getCourseId().toString())
                .param("studentId", workItem.job().getStudentId().toString())
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO)
                .setScale(8, RoundingMode.HALF_UP);
        jdbcClient.sql("""
                        UPDATE learning_plan plan SET status = 'COMPLETED'
                        WHERE plan.id = (
                            SELECT pointer.learning_plan_id
                            FROM learning_plan_current_pointer pointer
                            WHERE pointer.course_id = :courseId AND pointer.student_id = :studentId)
                          AND plan.status = 'ACTIVE'
                          AND NOT EXISTS (
                              SELECT 1 FROM learning_plan_item item
                              WHERE item.learning_plan_id = plan.id
                                AND item.status IN ('PENDING', 'IN_PROGRESS'))
                        """)
                .param("courseId", workItem.job().getCourseId().toString())
                .param("studentId", workItem.job().getStudentId().toString())
                .update();
        return completionRate;
    }

    private Map<UUID, SkillState> mergeSkillStates(
            WorkItem workItem, AnalysisInference inference) {
        LinkedHashMap<UUID, SkillState> result = new LinkedHashMap<>(workItem.currentSkills());
        for (SkillPrediction prediction : inference.skills().values()) {
            SkillState previous = result.get(prediction.skillId());
            String skillName = previous == null
                    ? skillName(prediction.skillId())
                    : previous.skillName();
            result.put(prediction.skillId(), new SkillState(
                    prediction.skillId(),
                    skillName,
                    prediction.masteryProbability(),
                    prediction.nextCorrectProbability(),
                    prediction.masteryModelVersion(),
                    prediction.nextModelVersion()));
        }
        return Collections.unmodifiableMap(result);
    }

    private String skillName(UUID skillId) {
        return jdbcClient.sql("SELECT name FROM knowledge_skill WHERE id = :skillId")
                .param("skillId", skillId.toString())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Knowledge skill " + skillId + " does not exist."));
    }

    private Map<UUID, SkillState> loadSnapshotSkills(UUID snapshotId) {
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
                .forEach(value -> result.put(value.skillId(), value));
        return result;
    }

    private static BigDecimal probability(double value) {
        return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, value)))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private Optional<PlanResult> findPlanResult(UUID jobId) {
        return jdbcClient.sql("""
                        SELECT lp.id AS plan_id, lp.plan_version, d.id AS diagnosis_id
                        FROM learning_plan lp
                        JOIN diagnosis d ON d.analysis_job_id = lp.source_job_id
                        WHERE lp.source_job_id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNumber) -> new PlanResult(
                        UUID.fromString(resultSet.getString("plan_id")),
                        resultSet.getLong("plan_version"),
                        UUID.fromString(resultSet.getString("diagnosis_id"))))
                .optional();
    }

    private Optional<PlanPointer> lockCurrentPlan(UUID courseId, UUID studentId) {
        return jdbcClient.sql("""
                        SELECT lpcp.learning_plan_id, lp.plan_version
                        FROM learning_plan_current_pointer lpcp
                        JOIN learning_plan lp ON lp.id = lpcp.learning_plan_id
                        WHERE lpcp.course_id = :courseId AND lpcp.student_id = :studentId
                        FOR UPDATE
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query((resultSet, rowNumber) -> new PlanPointer(
                        UUID.fromString(resultSet.getString("learning_plan_id")),
                        resultSet.getLong("plan_version")))
                .optional();
    }

    private List<PlanTaskFact> selectPlanTasks(WorkItem workItem, SnapshotResult snapshot) {
        List<SkillState> weakSkills = snapshot.skills().values().stream()
                .sorted(Comparator.comparing(SkillState::masteryProbability)
                        .thenComparing(value -> value.skillId().toString()))
                .limit(3)
                .toList();
        List<PlanTaskFact> result = new ArrayList<>();
        Set<UUID> usedQuestions = new LinkedHashSet<>();
        for (SkillState skill : weakSkills) {
            AnalysisWorkItemRepository.CandidateQuestion question = workItem.questions().stream()
                    .filter(candidate -> candidate.skillId().equals(skill.skillId()))
                    .filter(candidate -> usedQuestions.add(candidate.questionId()))
                    .min(Comparator.comparing(
                                    (AnalysisWorkItemRepository.CandidateQuestion candidate) ->
                                            candidate.difficulty()
                                                    .subtract(skill.masteryProbability())
                                                    .abs())
                            .thenComparing(candidate -> candidate.questionId().toString()))
                    .orElse(null);
            if (question != null) {
                result.add(new PlanTaskFact(
                        UUID.randomUUID(),
                        question.questionId(),
                        skill.skillId(),
                        "PRACTICE",
                        "Practice " + skill.skillName(),
                        "LOW_MASTERY",
                        skill.masteryProbability().add(new BigDecimal("0.15000000"))
                                .min(new BigDecimal("0.95000000")),
                        3));
            }
        }
        if (result.isEmpty()) {
            AnalysisWorkItemRepository.CandidateQuestion question = workItem.questions().get(0);
            result.add(new PlanTaskFact(
                    UUID.randomUUID(),
                    question.questionId(),
                    question.skillId(),
                    "REVIEW",
                    "Review " + question.skillName(),
                    "SPACED_REVIEW",
                    new BigDecimal("0.80000000"),
                    2));
        }
        return List.copyOf(result);
    }

    private static Set<ModelVersionRef> replaceDiagnosisModel(
            Set<ModelVersionRef> current, DiagnosisContent diagnosis) {
        LinkedHashSet<ModelVersionRef> result = new LinkedHashSet<>(current);
        result.removeIf(model -> model.getPurpose() == ModelVersionRef.PurposeEnum.DIAGNOSIS);
        String version = diagnosis.degraded()
                ? diagnosis.effectiveModel()
                : "ai-config:" + (diagnosis.aiConfigurationVersionId() == null
                        ? "environment"
                        : diagnosis.aiConfigurationVersionId());
        result.add(new ModelVersionRef(
                ModelVersionRef.PurposeEnum.DIAGNOSIS,
                diagnosis.degraded()
                        ? ModelVersionRef.FamilyEnum.TEMPLATE
                        : ModelVersionRef.FamilyEnum.OPENAI_COMPATIBLE,
                diagnosis.effectiveModel(),
                version,
                ModelInferenceService.sha256Artifact(version + ":" + diagnosis.effectiveModel()),
                "none"));
        return Collections.unmodifiableSet(result);
    }

    private ObjectNode predictionPayload(WorkItem workItem, AnalysisInference inference) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("computedAt", offset(Instant.now()));
        ArrayNode masteryChanges = payload.putArray("masteryChanges");
        for (SkillPrediction prediction : inference.skills().values()) {
            BigDecimal previous = Optional.ofNullable(workItem.currentSkills().get(prediction.skillId()))
                    .map(SkillState::masteryProbability)
                    .orElse(INITIAL_MASTERY);
            ObjectNode change = masteryChanges.addObject();
            change.put("knowledgeComponentId", prediction.skillId().toString());
            change.put("previousValue", previous);
            change.put("currentValue", prediction.masteryProbability());
            change.put("delta", prediction.masteryProbability().subtract(previous));
            change.put("method", prediction.estimator());
            change.put("modelVersionId", prediction.masteryModelVersion());
        }

        BigDecimal previousNext = workItem.currentTwin() == null
                ? INITIAL_NEXT
                : workItem.currentTwin().nextCorrectProbability();
        ObjectNode nextChange = payload.putObject("nextQuestionProbability");
        nextChange.put("previousValue", previousNext);
        nextChange.put("currentValue", inference.nextCorrectProbability());
        nextChange.put("delta", inference.nextCorrectProbability().subtract(previousNext));
        nextChange.put("method", modelFamily(
                inference.effectiveModels(), ModelVersionRef.PurposeEnum.NEXT_CORRECT));
        nextChange.put("modelVersionId", modelVersion(
                inference.effectiveModels(), ModelVersionRef.PurposeEnum.NEXT_CORRECT));

        BigDecimal previousRisk = workItem.currentTwin() == null
                ? INITIAL_RISK
                : workItem.currentTwin().riskProbability();
        ObjectNode riskChange = payload.putObject("riskProbability");
        riskChange.put("previousValue", previousRisk);
        riskChange.put("currentValue", inference.riskProbability());
        riskChange.put("delta", inference.riskProbability().subtract(previousRisk));
        riskChange.put("method", modelFamily(
                inference.effectiveModels(), ModelVersionRef.PurposeEnum.RISK));
        riskChange.put("modelVersionId", modelVersion(
                inference.effectiveModels(), ModelVersionRef.PurposeEnum.RISK));
        payload.put("degraded", inference.degraded());
        payload.set("degradedStages", objectMapper.valueToTree(inference.degradedStages()));
        return payload;
    }

    private void lockProcessingJob(UUID jobId, String expectedStage) {
        String stage = jdbcClient.sql("""
                        SELECT stage
                        FROM analysis_job
                        WHERE id = :jobId
                          AND status = 'PROCESSING'
                          AND lease_owner = :workerName
                        FOR UPDATE
                        """)
                .param("jobId", jobId.toString())
                .param("workerName", workerName)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Analysis job " + jobId + " is not processing."));
        if (expectedStage != null && !expectedStage.equals(stage)) {
            throw new IllegalStateException(
                    "Analysis job " + jobId + " is at stage " + stage + ", expected " + expectedStage + ".");
        }
    }

    private boolean predictionExists(UUID jobId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM risk_prediction
                        WHERE analysis_job_id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query(Long.class)
                .single() > 0;
    }

    private static String dataVersion(WorkItem workItem, DataVersionRef.SourceIdEnum source) {
        return workItem.job().getTrace().getDataVersions().stream()
                .filter(value -> value.getSourceId() == source)
                .map(DataVersionRef::getVersion)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Analysis trace is missing data version " + source + "."));
    }

    private static String modelVersion(
            Set<ModelVersionRef> models, ModelVersionRef.PurposeEnum purpose) {
        return model(models, purpose).getModelVersion();
    }

    private static String modelFamily(
            Set<ModelVersionRef> models, ModelVersionRef.PurposeEnum purpose) {
        return model(models, purpose).getFamily().getValue();
    }

    private static ModelVersionRef model(
            Set<ModelVersionRef> models, ModelVersionRef.PurposeEnum purpose) {
        return models.stream()
                .filter(value -> value.getPurpose() == purpose)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Effective model set is missing " + purpose + "."));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("A versioned workflow value could not be serialized.", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("A persisted workflow value contains invalid JSON.", exception);
        }
    }

    private static String offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC).toString();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean isAfter(Instant value, Instant reference) {
        return value != null && value.isAfter(reference);
    }

    private record StartState(
            String status,
            String stage,
            int attemptCount,
            Instant leaseExpiresAt,
            Instant nextAttemptAt) {}

    private record FailureState(String status, String stage) {}

    private record PersistedRisk(
            BigDecimal probability,
            String riskBand,
            BigDecimal baseValue,
            Map<String, Double> features,
            boolean calibrated,
            Set<String> degradedStages,
            Set<ModelVersionRef> effectiveModels) {}

    private record PointerState(
            UUID snapshotId, long lastAppliedAnswerSequence, long snapshotVersion) {}

    private record SnapshotRow(
            UUID snapshotId,
            long snapshotVersion,
            BigDecimal engagementScore,
            BigDecimal persistenceScore,
            BigDecimal planCompletionRate) {}

    private record PlanPointer(UUID planId, long version) {}

    private record PlanTaskFact(
            UUID taskId,
            UUID questionId,
            UUID skillId,
            String taskType,
            String title,
            String reasonCode,
            BigDecimal targetMastery,
            int targetCount) {}

    private record CompletionState(boolean degraded, String degradedStages) {}

    public enum LeaseDecision {
        ACQUIRED,
        DEFERRED,
        TERMINAL
    }

    public record ProcessingLease(LeaseDecision decision, String stage, int attemptCount) {}

    public record Failure(
            String failedStage,
            String errorCode,
            String message,
            boolean retryable) {}

    public record SnapshotResult(
            UUID snapshotId,
            long snapshotVersion,
            UUID previousSnapshotId,
            Map<UUID, SkillState> skills,
            BigDecimal engagementScore,
            BigDecimal persistenceScore,
            BigDecimal planCompletionRate) {

        public SnapshotResult {
            skills = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
        }
    }

    public record PlanResult(UUID planId, long planVersion, UUID diagnosisId) {}

    public record DiagnosisContent(
            String summary,
            List<String> strengths,
            List<String> concerns,
            List<String> recommendedActions,
            boolean toolCallVerified,
            boolean degraded,
            String provider,
            String requestedModel,
            String effectiveModel,
            UUID aiConfigurationVersionId,
            String degradationReason) {

        public DiagnosisContent {
            strengths = List.copyOf(strengths);
            concerns = List.copyOf(concerns);
            recommendedActions = List.copyOf(recommendedActions);
            if (provider == null || provider.isBlank()
                    || requestedModel == null || requestedModel.isBlank()
                    || effectiveModel == null || effectiveModel.isBlank()) {
                throw new IllegalArgumentException("Diagnosis model metadata is required.");
            }
            if (degraded && (degradationReason == null || degradationReason.isBlank())) {
                throw new IllegalArgumentException("A degraded diagnosis requires a reason.");
            }
            if (!degraded && degradationReason != null) {
                throw new IllegalArgumentException("A successful diagnosis cannot have a degradation reason.");
            }
        }
    }
}
