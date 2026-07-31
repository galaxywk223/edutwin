package com.edutwin.bootstrap;

import com.edutwin.bootstrap.DemoSeedRegistry.Artifact;
import com.edutwin.bootstrap.DemoSeedRegistry.Dataset;
import com.edutwin.bootstrap.DemoSeedRegistry.Deployment;
import com.edutwin.bootstrap.DemoSeedRegistry.Model;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

final class DemoInitialStateImporter {

    private static final int EXPECTED_STUDENTS = 2_000;
    private static final int SNAPSHOTS_PER_ENROLLMENT = 6;
    private static final int BATCH_SIZE = 1_000;
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final String TEMPLATE_DIAGNOSIS = "template-diagnosis-v1";
    private static final List<String> LIFECYCLE_EVENTS = List.of(
            "job.queued",
            "job.processing",
            "predictions.computed",
            "twin.snapshot-created",
            "learning-plan.created",
            "job.completed");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final UnaryOperator<OffsetDateTime> businessTime;
    private final OffsetDateTime currentPlanAnchor;

    DemoInitialStateImporter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            UnaryOperator<OffsetDateTime> businessTime,
            OffsetDateTime currentPlanAnchor) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.businessTime = businessTime;
        this.currentPlanAnchor = currentPlanAnchor;
    }

    Seed load(
            Path root,
            DemoSeedRegistry registry,
            Map<String, UUID> enrollmentCourses,
            Map<String, Map<Long, UUID>> eventIdsBySequence,
            Set<UUID> skillIds,
            Set<UUID> questionIds,
            String demoManifestSha256) throws IOException {
        Path manifestPath = root.resolve("database/initial-states-manifest.json").normalize();
        Path statesPath = root.resolve("database/initial_states.jsonl").normalize();
        if (!manifestPath.startsWith(root) || !statesPath.startsWith(root)
                || !Files.isRegularFile(manifestPath) || !Files.isRegularFile(statesPath)) {
            throw new IllegalStateException("Initial state projection files are missing.");
        }
        JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
        if (manifest.path("schemaVersion").asInt(-1) != 2
                || !"edutwin-initial-state-projection".equals(manifest.path("kind").asText())
                || !"frozen-model-bootstrap-v2".equals(manifest.path("generatorVersion").asText())
                || manifest.path("seed").asInt(-1) != 42
                || manifest.path("studentCount").asInt(-1) != EXPECTED_STUDENTS
                || manifest.path("enrollmentCount").asInt(-1) < 8_000
                || manifest.path("skillsPerEnrollment").asInt(-1) != 3
                || manifest.path("snapshotsPerEnrollment").asInt(-1)
                        != SNAPSHOTS_PER_ENROLLMENT
                || manifest.path("eventsPerEnrollmentRange").size() != 2
                || manifest.path("eventsPerEnrollmentRange").get(0).asInt(-1) != 18
                || manifest.path("eventsPerEnrollmentRange").get(1).asInt(-1) != 42) {
            throw new IllegalStateException("Initial state manifest violates the frozen contract.");
        }
        validateManifestEntry(manifest.path("output"), statesPath, "initial states");
        JsonNode demoEntry = manifest.path("demoManifest");
        if (!demoManifestSha256.equals(demoEntry.path("sha256").asText())) {
            throw new IllegalStateException("Initial states reference a different demo manifest.");
        }
        requireManifestReference(manifest.path("knowledgeFreezeManifest"), "knowledge freeze");
        requireManifestReference(manifest.path("riskFreezeManifest"), "risk freeze");

        List<Map<String, Object>> dataVersions = dataVersionReferences(registry);
        List<Map<String, Object>> requestedModels = activeModelReferences(registry);
        List<Map<String, Object>> effectiveModels = effectiveModelReferences(requestedModels);
        Map<String, String> versionByPurpose = new HashMap<>();
        for (Map<String, Object> reference : requestedModels) {
            versionByPurpose.put((String) reference.get("purpose"),
                    (String) reference.get("modelVersion"));
        }

        ArrayList<InitialState> states = new ArrayList<>(
                enrollmentCourses.size() * SNAPSHOTS_PER_ENROLLMENT);
        Map<String, Set<Integer>> seenVersions = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(statesPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    throw new IllegalStateException("Initial state JSONL contains a blank row.");
                }
                InitialState state = objectMapper.readValue(line, InitialState.class);
                validateState(
                        state,
                        enrollmentCourses,
                        eventIdsBySequence,
                        skillIds,
                        questionIds,
                        versionByPurpose);
                String enrollmentKey = enrollmentKey(state.courseId(), state.studentId());
                if (!seenVersions.computeIfAbsent(enrollmentKey, ignored -> new LinkedHashSet<>())
                        .add(state.snapshotVersion())) {
                    throw new IllegalStateException(
                            "Initial state snapshot is duplicated: " + enrollmentKey);
                }
                states.add(state);
            }
        }
        Set<Integer> expectedVersions = Set.of(1, 2, 3, 4, 5, 6);
        if (states.size() != enrollmentCourses.size() * SNAPSHOTS_PER_ENROLLMENT
                || !seenVersions.keySet().equals(enrollmentCourses.keySet())
                || seenVersions.values().stream().anyMatch(value -> !value.equals(expectedVersions))) {
            throw new IllegalStateException(
                    "Initial states do not cover six versions for every active enrollment.");
        }
        states.sort(Comparator.comparing((InitialState value) -> value.studentId().toString())
                .thenComparing(value -> value.courseId().toString())
                .thenComparingInt(InitialState::snapshotVersion));
        return new Seed(
                sha256(manifestPath),
                sha256(statesPath),
                List.copyOf(states),
                List.copyOf(dataVersions),
                List.copyOf(requestedModels),
                List.copyOf(effectiveModels),
                dataVersion(dataVersions, "ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED"),
                dataVersion(dataVersions, "OULAD"));
    }

    void insert(Seed seed, DemoSeedRegistry registry) {
        String dataVersionsJson = json(seed.dataVersions());
        String requestedModelsJson = json(seed.requestedModels());
        String effectiveModelsJson = json(seed.effectiveModels());

        List<Projection> projections = seed.states().stream()
                .map(state -> projection(state, dataVersionsJson, requestedModelsJson,
                        effectiveModelsJson))
                .toList();

        batch("""
                INSERT INTO analysis_job(
                    id, answer_event_id, course_id, student_id, target_snapshot_id,
                    correlation_id, status, stage, degraded, degraded_stages,
                    attempt_count, data_versions, requested_model_versions,
                    effective_model_versions, created_at, started_at, completed_at, version)
                VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', 'COMPLETED', TRUE, ?,
                        1, ?, ?, ?, ?, ?, ?, 6)
                ON DUPLICATE KEY UPDATE answer_event_id=VALUES(answer_event_id),
                    course_id=VALUES(course_id), student_id=VALUES(student_id),
                    target_snapshot_id=VALUES(target_snapshot_id),
                    correlation_id=VALUES(correlation_id), status='COMPLETED', stage='COMPLETED',
                    degraded=TRUE, degraded_stages=VALUES(degraded_stages),
                    data_versions=VALUES(data_versions),
                    requested_model_versions=VALUES(requested_model_versions),
                    effective_model_versions=VALUES(effective_model_versions),
                    created_at=VALUES(created_at), started_at=VALUES(started_at),
                    completed_at=VALUES(completed_at)
                """, projections, (statement, row) -> {
                    statement.setString(1, row.jobId().toString());
                    statement.setString(2, row.state().answerEventId().toString());
                    statement.setString(3, row.state().courseId().toString());
                    statement.setString(4, row.state().studentId().toString());
                    statement.setString(5, row.snapshotId().toString());
                    statement.setString(6, row.correlationId().toString());
                    statement.setString(7, "[\"DEEPSEEK_UNAVAILABLE\"]");
                    statement.setString(8, dataVersionsJson);
                    statement.setString(9, requestedModelsJson);
                    statement.setString(10, effectiveModelsJson);
                    OffsetDateTime capturedAt = businessTime(row.state().capturedAt());
                    statement.setTimestamp(11, timestamp(capturedAt.minusSeconds(2)));
                    statement.setTimestamp(12, timestamp(capturedAt.minusSeconds(1)));
                    statement.setTimestamp(13, timestamp(capturedAt));
                });

        List<JobEvent> events = new ArrayList<>(projections.size() * LIFECYCLE_EVENTS.size());
        for (Projection projection : projections) {
            for (int index = 0; index < LIFECYCLE_EVENTS.size(); index++) {
                long sequence = index + 1L;
                events.add(new JobEvent(
                        stableId("bootstrap-job-event", projection.jobId().toString(),
                                Long.toString(sequence)),
                        projection.jobId(),
                        sequence,
                        LIFECYCLE_EVENTS.get(index),
                        businessTime(projection.state().capturedAt()).minusSeconds(
                                LIFECYCLE_EVENTS.size() - sequence)));
            }
        }
        batch("""
                INSERT INTO analysis_job_event(
                    id, analysis_job_id, sequence_no, event_type, payload, occurred_at)
                VALUES (?, ?, ?, ?, '{"bootstrap":true}', ?)
                ON DUPLICATE KEY UPDATE event_type=VALUES(event_type),
                    payload=VALUES(payload), occurred_at=VALUES(occurred_at)
                """, events, (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.jobId().toString());
                    statement.setLong(3, row.sequence());
                    statement.setString(4, row.eventType());
                    statement.setTimestamp(5, timestamp(row.occurredAt()));
                });

        List<KnowledgePredictionRow> predictions = new ArrayList<>(
                projections.size() * 3);
        List<SnapshotSkillRow> snapshotSkills = new ArrayList<>(
                projections.size() * 3);
        for (Projection projection : projections) {
            for (SkillState skill : projection.state().knowledge().skills()) {
                predictions.add(new KnowledgePredictionRow(projection, skill));
                snapshotSkills.add(new SnapshotSkillRow(projection, skill));
            }
        }
        batch("""
                INSERT INTO knowledge_prediction(
                    id, analysis_job_id, answer_event_id, course_id, student_id, skill_id,
                    mastery_probability, next_correct_probability, mastery_model_version,
                    next_model_version, data_version, degraded, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)
                ON DUPLICATE KEY UPDATE mastery_probability=VALUES(mastery_probability),
                    next_correct_probability=VALUES(next_correct_probability),
                    mastery_model_version=VALUES(mastery_model_version),
                    next_model_version=VALUES(next_model_version), data_version=VALUES(data_version),
                    degraded=FALSE, created_at=VALUES(created_at)
                """, predictions, (statement, row) -> {
                    Projection projection = row.projection();
                    SkillState skill = row.skill();
                    statement.setString(1, stableId("bootstrap-knowledge-prediction",
                            projection.jobId().toString(), skill.skillId().toString()).toString());
                    statement.setString(2, projection.jobId().toString());
                    statement.setString(3, projection.state().answerEventId().toString());
                    statement.setString(4, projection.state().courseId().toString());
                    statement.setString(5, projection.state().studentId().toString());
                    statement.setString(6, skill.skillId().toString());
                    statement.setBigDecimal(7, skill.probability());
                    statement.setBigDecimal(8,
                            projection.state().knowledge().nextCorrectProbability());
                    statement.setString(9,
                            projection.state().knowledge().masteryModelVersion());
                    statement.setString(10, projection.state().knowledge().nextModelVersion());
                    statement.setString(11, seed.assistmentsVersion());
                    statement.setTimestamp(12, timestamp(
                            businessTime(projection.state().capturedAt())));
                });

        batch("""
                INSERT INTO risk_prediction(
                    id, analysis_job_id, answer_event_id, course_id, student_id,
                    calibrated_probability, risk_band, base_value, feature_values,
                    model_version, data_version, calibrated, degraded, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, FALSE, ?)
                ON DUPLICATE KEY UPDATE calibrated_probability=VALUES(calibrated_probability),
                    risk_band=VALUES(risk_band), base_value=VALUES(base_value),
                    feature_values=VALUES(feature_values), model_version=VALUES(model_version),
                    data_version=VALUES(data_version), calibrated=TRUE, degraded=FALSE,
                    created_at=VALUES(created_at)
                """, projections, (statement, row) -> {
                    statement.setString(1, stableId("bootstrap-risk-prediction",
                            row.jobId().toString()).toString());
                    statement.setString(2, row.jobId().toString());
                    statement.setString(3, row.state().answerEventId().toString());
                    statement.setString(4, row.state().courseId().toString());
                    statement.setString(5, row.state().studentId().toString());
                    statement.setBigDecimal(6, row.state().risk().probability());
                    statement.setString(7, row.state().risk().riskBand());
                    statement.setBigDecimal(8, row.state().risk().baseValue());
                    statement.setString(9, json(row.state().risk().features()));
                    statement.setString(10, row.state().risk().modelVersion());
                    statement.setString(11, seed.ouladVersion());
                    statement.setTimestamp(12, timestamp(businessTime(row.state().capturedAt())));
                });

        batch("""
                INSERT INTO twin_snapshot(
                    id, course_id, student_id, snapshot_version, answer_event_id,
                    analysis_job_id, knowledge_mastery, next_correct_probability,
                    risk_probability, engagement_score, persistence_score,
                    plan_completion_rate, data_versions, model_versions, degraded, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)
                ON DUPLICATE KEY UPDATE answer_event_id=VALUES(answer_event_id),
                    analysis_job_id=VALUES(analysis_job_id),
                    knowledge_mastery=VALUES(knowledge_mastery),
                    next_correct_probability=VALUES(next_correct_probability),
                    risk_probability=VALUES(risk_probability),
                    engagement_score=VALUES(engagement_score),
                    persistence_score=VALUES(persistence_score),
                    plan_completion_rate=VALUES(plan_completion_rate),
                    data_versions=VALUES(data_versions), model_versions=VALUES(model_versions),
                    degraded=FALSE, created_at=VALUES(created_at)
                """, projections, (statement, row) -> {
                    statement.setString(1, row.snapshotId().toString());
                    statement.setString(2, row.state().courseId().toString());
                    statement.setString(3, row.state().studentId().toString());
                    statement.setInt(4, row.state().snapshotVersion());
                    statement.setString(5, row.state().answerEventId().toString());
                    statement.setString(6, row.jobId().toString());
                    statement.setString(7, json(row.state().knowledge().skills()));
                    statement.setBigDecimal(8, row.state().knowledge().nextCorrectProbability());
                    statement.setBigDecimal(9, row.state().risk().probability());
                    statement.setBigDecimal(10, row.state().engagementScore());
                    statement.setBigDecimal(11, row.state().persistenceScore());
                    statement.setBigDecimal(12, row.state().planCompletionRate());
                    statement.setString(13, dataVersionsJson);
                    statement.setString(14, effectiveModelsJson);
                    statement.setTimestamp(15, timestamp(businessTime(row.state().capturedAt())));
                });

        batch("""
                INSERT INTO twin_snapshot_skill(
                    snapshot_id, skill_id, mastery_probability, next_correct_probability,
                    mastery_model_version, next_model_version)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE mastery_probability=VALUES(mastery_probability),
                    next_correct_probability=VALUES(next_correct_probability),
                    mastery_model_version=VALUES(mastery_model_version),
                    next_model_version=VALUES(next_model_version)
                """, snapshotSkills, (statement, row) -> {
                    statement.setString(1, row.projection().snapshotId().toString());
                    statement.setString(2, row.skill().skillId().toString());
                    statement.setBigDecimal(3, row.skill().probability());
                    statement.setBigDecimal(4,
                            row.projection().state().knowledge().nextCorrectProbability());
                    statement.setString(5,
                            row.projection().state().knowledge().masteryModelVersion());
                    statement.setString(6,
                            row.projection().state().knowledge().nextModelVersion());
                });

        batch("""
                INSERT INTO twin_current_pointer(
                    course_id, student_id, snapshot_id, last_applied_answer_sequence, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    snapshot_id=IF(last_applied_answer_sequence <= VALUES(last_applied_answer_sequence),
                        VALUES(snapshot_id), snapshot_id),
                    updated_at=IF(last_applied_answer_sequence <= VALUES(last_applied_answer_sequence),
                        VALUES(updated_at), updated_at),
                    last_applied_answer_sequence=GREATEST(
                        last_applied_answer_sequence, VALUES(last_applied_answer_sequence))
                """, projections, (statement, row) -> {
                    statement.setString(1, row.state().courseId().toString());
                    statement.setString(2, row.state().studentId().toString());
                    statement.setString(3, row.snapshotId().toString());
                    statement.setLong(4, row.state().eventSequence());
                    statement.setTimestamp(5, timestamp(businessTime(row.state().capturedAt())));
                });

        batch("""
                INSERT INTO learning_plan(
                    id, course_id, student_id, plan_version, source_snapshot_id,
                    source_job_id, rule_version, status, valid_until,
                    data_versions, model_versions, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status=VALUES(status), valid_until=VALUES(valid_until),
                    data_versions=VALUES(data_versions), model_versions=VALUES(model_versions),
                    created_at=VALUES(created_at)
                """, projections, (statement, row) -> {
                    statement.setString(1, row.planId().toString());
                    statement.setString(2, row.state().courseId().toString());
                    statement.setString(3, row.state().studentId().toString());
                    statement.setInt(4, row.state().snapshotVersion());
                    statement.setString(5, row.snapshotId().toString());
                    statement.setString(6, row.jobId().toString());
                    statement.setString(7, row.state().plan().ruleVersion());
                    statement.setString(8, row.state().snapshotVersion() == SNAPSHOTS_PER_ENROLLMENT
                            ? "ACTIVE" : "SUPERSEDED");
                    statement.setTimestamp(9, timestamp(planScheduleTime(row.state().capturedAt(),
                            row.state().snapshotVersion())
                            .plusDays(row.state().plan().validDays())));
                    statement.setString(10, dataVersionsJson);
                    statement.setString(11, effectiveModelsJson);
                    statement.setTimestamp(12, timestamp(businessTime(row.state().capturedAt())));
                });

        List<PlanItemRow> planItems = new ArrayList<>();
        for (Projection projection : projections) {
            Map<UUID, String> skillNames = projection.state().knowledge().skills().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            SkillState::skillId, SkillState::skillName));
            for (PlanTask task : projection.state().plan().tasks()) {
                planItems.add(new PlanItemRow(
                        projection,
                        task,
                        "Practice " + skillNames.get(task.skillId())));
            }
        }
        batch("""
                INSERT INTO learning_plan_item(
                    id, learning_plan_id, ordinal, question_id, skill_id, task_type,
                    title, rationale_code, target_mastery, target_count,
                    completed_count, status, due_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE title=VALUES(title), rationale_code=VALUES(rationale_code),
                    target_mastery=VALUES(target_mastery), target_count=VALUES(target_count),
                    completed_count=VALUES(completed_count), status=VALUES(status),
                    due_at=VALUES(due_at)
                """, planItems, (statement, row) -> {
                    statement.setString(1, stableId("bootstrap-plan-item",
                            row.projection().planId().toString(),
                            Integer.toString(row.task().ordinal())).toString());
                    statement.setString(2, row.projection().planId().toString());
                    statement.setInt(3, row.task().ordinal());
                    statement.setString(4, row.task().questionId().toString());
                    statement.setString(5, row.task().skillId().toString());
                    statement.setString(6, row.task().taskType());
                    statement.setString(7, row.title());
                    statement.setString(8, row.task().reasonCode());
                    statement.setBigDecimal(9, row.task().targetMastery());
                    statement.setInt(10, row.task().targetCount());
                    statement.setInt(11, row.task().completedCount());
                    statement.setString(12, row.task().status());
                    statement.setTimestamp(13, timestamp(planScheduleTime(
                            row.projection().state().capturedAt(),
                            row.projection().state().snapshotVersion())
                            .plusDays(row.task().dueOffsetDays())));
                });
        batch("""
                INSERT INTO learning_plan_current_pointer(
                    course_id, student_id, learning_plan_id, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    learning_plan_id=IF(updated_at <= VALUES(updated_at),
                        VALUES(learning_plan_id), learning_plan_id),
                    updated_at=GREATEST(updated_at, VALUES(updated_at))
                """, projections, (statement, row) -> {
                    statement.setString(1, row.state().courseId().toString());
                    statement.setString(2, row.state().studentId().toString());
                    statement.setString(3, row.planId().toString());
                    statement.setTimestamp(4, timestamp(businessTime(row.state().capturedAt())));
                });

        batch("""
                INSERT INTO diagnosis(
                    id, course_id, student_id, snapshot_id, analysis_job_id,
                    schema_version, provider, requested_model, effective_model,
                    structured_content, degraded, created_at)
                VALUES (?, ?, ?, ?, ?, '1.0', 'TEMPLATE', 'deepseek-v4-flash', ?, ?, TRUE, ?)
                ON DUPLICATE KEY UPDATE structured_content=VALUES(structured_content),
                    degraded=TRUE, created_at=VALUES(created_at)
                """, projections, (statement, row) -> {
                    statement.setString(1, row.diagnosisId().toString());
                    statement.setString(2, row.state().courseId().toString());
                    statement.setString(3, row.state().studentId().toString());
                    statement.setString(4, row.snapshotId().toString());
                    statement.setString(5, row.jobId().toString());
                    statement.setString(6, row.state().diagnosis().effectiveModel());
                    statement.setString(7, json(row.state().diagnosis()));
                    statement.setTimestamp(8, timestamp(businessTime(row.state().capturedAt())));
                });

        List<EvidenceRow> evidenceRows = new ArrayList<>(projections.size() * 5);
        for (Projection projection : projections) {
            for (RiskEvidence evidence : projection.state().risk().evidence()) {
                evidenceRows.add(new EvidenceRow(projection, evidence));
            }
        }
        batch("""
                INSERT INTO diagnosis_evidence(
                    id, diagnosis_id, rank_no, feature_name, raw_value, direction,
                    contribution, base_value, output_unit, risk_model_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE feature_name=VALUES(feature_name),
                    raw_value=VALUES(raw_value), direction=VALUES(direction),
                    contribution=VALUES(contribution), base_value=VALUES(base_value),
                    output_unit=VALUES(output_unit), risk_model_version=VALUES(risk_model_version)
                """, evidenceRows, (statement, row) -> {
                    RiskEvidence evidence = row.evidence();
                    statement.setString(1, stableId("bootstrap-diagnosis-evidence",
                            row.projection().diagnosisId().toString(),
                            Integer.toString(evidence.rank())).toString());
                    statement.setString(2, row.projection().diagnosisId().toString());
                    statement.setInt(3, evidence.rank());
                    statement.setString(4, evidence.featureName());
                    statement.setString(5, json(evidence.rawValue()));
                    statement.setString(6, evidence.direction());
                    statement.setBigDecimal(7, evidence.contribution());
                    statement.setBigDecimal(8, evidence.baseValue());
                    statement.setString(9, evidence.outputUnit());
                    statement.setString(10, row.projection().state().risk().modelVersion());
                });

        insertCourseRisk(projections);
    }

    void verifyPersisted(Seed seed) {
        long enrollmentCount = seed.states().size() / SNAPSHOTS_PER_ENROLLMENT;
        requireCount("bootstrap analysis jobs", seed.states().size(),
                """
                SELECT COUNT(*)
                FROM analysis_job aj
                WHERE aj.status = 'COMPLETED'
                  AND aj.degraded_stages = JSON_ARRAY('DEEPSEEK_UNAVAILABLE')
                  AND EXISTS (
                      SELECT 1 FROM analysis_job_event aje
                      WHERE aje.analysis_job_id = aj.id
                        AND aje.sequence_no = 1
                        AND aje.payload = JSON_OBJECT('bootstrap', TRUE))
                """);
        requireCount("bootstrap lifecycle events", seed.states().size() * 6L,
                "SELECT COUNT(*) FROM analysis_job_event "
                        + "WHERE payload = JSON_OBJECT('bootstrap', TRUE)");
        requireCount("bootstrap knowledge predictions", seed.states().size() * 3L,
                """
                SELECT COUNT(*)
                FROM knowledge_prediction kp
                WHERE EXISTS (
                    SELECT 1 FROM analysis_job_event aje
                    WHERE aje.analysis_job_id = kp.analysis_job_id
                      AND aje.sequence_no = 1
                      AND aje.payload = JSON_OBJECT('bootstrap', TRUE))
                """);
        requireCount("bootstrap risk predictions", seed.states().size(),
                """
                SELECT COUNT(*)
                FROM risk_prediction rp
                WHERE EXISTS (
                    SELECT 1 FROM analysis_job_event aje
                    WHERE aje.analysis_job_id = rp.analysis_job_id
                      AND aje.sequence_no = 1
                      AND aje.payload = JSON_OBJECT('bootstrap', TRUE))
                """);
        requireCount("bootstrap twin snapshots", seed.states().size(),
                "SELECT COUNT(*) FROM twin_snapshot WHERE snapshot_version BETWEEN 1 AND 6");
        requireCount("bootstrap snapshot skills", seed.states().size() * 3L,
                """
                SELECT COUNT(*)
                FROM twin_snapshot_skill tss
                JOIN twin_snapshot ts ON ts.id = tss.snapshot_id
                WHERE ts.snapshot_version BETWEEN 1 AND 6
                """);
        requireCount("bootstrap current twin pointers", enrollmentCount, """
                SELECT COUNT(*)
                FROM twin_current_pointer tcp
                JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                WHERE ts.snapshot_version >= 6
                """);
        requireCount("bootstrap learning plans", seed.states().size(),
                "SELECT COUNT(*) FROM learning_plan WHERE plan_version BETWEEN 1 AND 6");
        requireCount("bootstrap active learning plans", enrollmentCount,
                "SELECT COUNT(*) FROM learning_plan WHERE plan_version >= 6 AND status = 'ACTIVE'");
        requireCount("bootstrap superseded learning plans", enrollmentCount * 5L,
                "SELECT COUNT(*) FROM learning_plan WHERE plan_version < 6 "
                        + "AND status = 'SUPERSEDED'");
        requireCount("bootstrap current plan pointers", enrollmentCount, """
                SELECT COUNT(*)
                FROM learning_plan_current_pointer lpcp
                JOIN learning_plan lp ON lp.id = lpcp.learning_plan_id
                WHERE lp.plan_version >= 6
                """);
        requireCount("bootstrap diagnoses", seed.states().size(),
                """
                SELECT COUNT(*)
                FROM diagnosis d
                WHERE d.effective_model = ? AND d.degraded = TRUE
                  AND EXISTS (
                      SELECT 1 FROM analysis_job_event aje
                      WHERE aje.analysis_job_id = d.analysis_job_id
                        AND aje.sequence_no = 1
                        AND aje.payload = JSON_OBJECT('bootstrap', TRUE))
                """,
                TEMPLATE_DIAGNOSIS);
        requireCount("bootstrap diagnosis evidence", seed.states().size() * 5L,
                """
                SELECT COUNT(*)
                FROM diagnosis_evidence de
                JOIN diagnosis d ON d.id = de.diagnosis_id
                WHERE EXISTS (
                    SELECT 1 FROM analysis_job_event aje
                    WHERE aje.analysis_job_id = d.analysis_job_id
                      AND aje.sequence_no = 1
                      AND aje.payload = JSON_OBJECT('bootstrap', TRUE))
                """);
        requireCount("bootstrap course risk projections", 20,
                "SELECT COUNT(*) FROM course_risk_current WHERE student_count = "
                        + "low_count + medium_count + high_count");
        long planItems = count("""
                SELECT COUNT(*)
                FROM learning_plan_item lpi
                JOIN learning_plan lp ON lp.id = lpi.learning_plan_id
                WHERE lp.plan_version BETWEEN 1 AND 6
                """);
        if (planItems < seed.states().size() || planItems > seed.states().size() * 5L) {
            throw new IllegalStateException("Persisted bootstrap plan item count differs: "
                    + planItems);
        }
        requireCount("trace-complete current twins", enrollmentCount, """
                SELECT COUNT(*)
                FROM twin_current_pointer tcp
                JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                JOIN analysis_job aj ON aj.id = ts.analysis_job_id
                JOIN learning_plan_current_pointer lpcp
                  ON lpcp.course_id = tcp.course_id AND lpcp.student_id = tcp.student_id
                JOIN diagnosis d ON d.analysis_job_id = aj.id
                WHERE ts.answer_event_id = aj.answer_event_id
                  AND ts.id = aj.target_snapshot_id
                  AND ts.snapshot_version >= 6
                """);
    }

    private void insertCourseRisk(List<Projection> projections) {
        Map<UUID, List<Projection>> byCourse = new LinkedHashMap<>();
        for (Projection projection : projections) {
            if (projection.state().snapshotVersion() != SNAPSHOTS_PER_ENROLLMENT) {
                continue;
            }
            byCourse.computeIfAbsent(projection.state().courseId(), ignored -> new ArrayList<>())
                    .add(projection);
        }
        List<CourseRiskRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, List<Projection>> entry : byCourse.entrySet()) {
            List<Projection> values = entry.getValue();
            BigDecimal total = values.stream()
                    .map(value -> value.state().risk().probability())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long low = values.stream()
                    .filter(value -> "LOW".equals(value.state().risk().riskBand())).count();
            long medium = values.stream()
                    .filter(value -> "MEDIUM".equals(value.state().risk().riskBand())).count();
            long high = values.stream()
                    .filter(value -> "HIGH".equals(value.state().risk().riskBand())).count();
            Projection source = values.get(values.size() - 1);
            rows.add(new CourseRiskRow(
                    entry.getKey(),
                    total.divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP),
                    low,
                    medium,
                    high,
                    values.size(),
                    source.jobId(),
                    businessTime(source.state().capturedAt())));
        }
        batch("""
                INSERT INTO course_risk_current(
                    course_id, mean_risk, low_count, medium_count, high_count,
                    student_count, aggregation_version, source_job_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    mean_risk=IF(aggregation_version <= 1, VALUES(mean_risk), mean_risk),
                    low_count=IF(aggregation_version <= 1, VALUES(low_count), low_count),
                    medium_count=IF(aggregation_version <= 1, VALUES(medium_count), medium_count),
                    high_count=IF(aggregation_version <= 1, VALUES(high_count), high_count),
                    student_count=IF(aggregation_version <= 1, VALUES(student_count), student_count),
                    source_job_id=IF(aggregation_version <= 1, VALUES(source_job_id), source_job_id),
                    updated_at=IF(aggregation_version <= 1, VALUES(updated_at), updated_at)
                """, rows, (statement, row) -> {
                    statement.setString(1, row.courseId().toString());
                    statement.setBigDecimal(2, row.meanRisk());
                    statement.setLong(3, row.lowCount());
                    statement.setLong(4, row.mediumCount());
                    statement.setLong(5, row.highCount());
                    statement.setInt(6, row.studentCount());
                    statement.setString(7, row.sourceJobId().toString());
                    statement.setTimestamp(8, timestamp(row.updatedAt()));
                });
    }

    private Projection projection(
            InitialState state,
            String dataVersions,
            String requestedModels,
            String effectiveModels) {
        UUID jobId = stableId("bootstrap-analysis-job", state.studentId().toString(),
                state.courseId().toString(), Integer.toString(state.snapshotVersion()));
        return new Projection(
                state,
                jobId,
                stableId("bootstrap-snapshot", jobId.toString()),
                stableId("bootstrap-correlation", jobId.toString()),
                stableId("bootstrap-plan", jobId.toString()),
                stableId("bootstrap-diagnosis", jobId.toString()),
                dataVersions,
                requestedModels,
                effectiveModels);
    }

    private void validateState(
            InitialState state,
            Map<String, UUID> enrollmentCourses,
            Map<String, Map<Long, UUID>> eventIdsBySequence,
            Set<UUID> skillIds,
            Set<UUID> questionIds,
            Map<String, String> versionByPurpose) {
        String enrollmentKey = enrollmentKey(state.courseId(), state.studentId());
        Map<Long, UUID> enrollmentEvents = eventIdsBySequence.get(enrollmentKey);
        if (state.schemaVersion() != 2
                || state.snapshotVersion() < 1
                || state.snapshotVersion() > SNAPSHOTS_PER_ENROLLMENT
                || !state.courseId().equals(enrollmentCourses.get(enrollmentKey))
                || enrollmentEvents == null
                || !state.answerEventId().equals(enrollmentEvents.get(state.eventSequence()))
                || state.knowledge() == null
                || state.risk() == null
                || state.plan() == null
                || state.diagnosis() == null) {
            throw new IllegalStateException("An initial state has invalid identity or lineage.");
        }
        OffsetDateTime captured = state.capturedAt().withOffsetSameInstant(ZoneOffset.UTC);
        if (captured.getYear() < 2020
                || state.knowledge().skills() == null
                || state.knowledge().skills().size() != 3
                || !versionByPurpose.get("MASTERY")
                        .equals(state.knowledge().masteryModelVersion())
                || !versionByPurpose.get("NEXT_CORRECT")
                        .equals(state.knowledge().nextModelVersion())
                || !versionByPurpose.get("RISK").equals(state.risk().modelVersion())
                || !versionByPurpose.get("EXPLANATION")
                        .equals(state.risk().explainerModelVersion())
                || !versionByPurpose.get("PLAN_RULES").equals(state.plan().ruleVersion())
                || !TEMPLATE_DIAGNOSIS.equals(state.diagnosis().effectiveModel())
                || state.diagnosis().toolCallVerified()) {
            throw new IllegalStateException("An initial state differs from frozen model versions.");
        }
        Set<UUID> stateSkills = unique(
                state.knowledge().skills(), SkillState::skillId, "initial state skills");
        if (stateSkills.size() != 3 || !skillIds.containsAll(stateSkills)) {
            throw new IllegalStateException("Initial state mastery is outside the course skill set.");
        }
        for (SkillState skill : state.knowledge().skills()) {
            probability(skill.probability(), "mastery probability");
            requireText(skill.skillName(), "skill name");
        }
        probability(state.knowledge().nextCorrectProbability(), "next-correct probability");
        probability(state.risk().probability(), "risk probability");
        probability(state.engagementScore(), "engagement score");
        probability(state.persistenceScore(), "persistence score");
        probability(state.planCompletionRate(), "plan completion rate");
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(state.risk().riskBand())
                || !state.risk().calibrated()
                || state.risk().features() == null
                || state.risk().features().isEmpty()
                || state.risk().evidence() == null
                || state.risk().evidence().size() != 5) {
            throw new IllegalStateException("Initial state risk contract differs.");
        }
        Set<String> evidenceNames = new HashSet<>();
        for (int index = 0; index < state.risk().evidence().size(); index++) {
            RiskEvidence evidence = state.risk().evidence().get(index);
            String expectedDirection = evidence.contribution().signum() > 0
                    ? "INCREASES_RISK" : "DECREASES_RISK";
            if (evidence.rank() != index + 1
                    || !evidenceNames.add(evidence.featureName())
                    || evidence.contribution().signum() == 0
                    || !expectedDirection.equals(evidence.direction())
                    || !"LOG_ODDS".equals(evidence.outputUnit())) {
                throw new IllegalStateException("Initial SHAP evidence contract differs.");
            }
        }
        if (state.plan().validDays() != 7
                || state.plan().tasks() == null
                || state.plan().tasks().isEmpty()
                || state.plan().tasks().size() > 5) {
            throw new IllegalStateException("Initial plan contract differs.");
        }
        Set<Integer> ordinals = new HashSet<>();
        for (PlanTask task : state.plan().tasks()) {
            if (!ordinals.add(task.ordinal())
                    || task.ordinal() < 1
                    || task.ordinal() > state.plan().tasks().size()
                    || !questionIds.contains(task.questionId())
                    || !skillIds.contains(task.skillId())
                    || !Set.of("PRACTICE", "REVIEW").contains(task.taskType())
                    || !Set.of("LOW_MASTERY", "HIGH_RISK_CONTRIBUTION",
                            "STABILITY_RECOVERY", "SPACED_REVIEW")
                            .contains(task.reasonCode())
                    || task.targetCount() < 1
                    || task.completedCount() < 0
                    || task.completedCount() > task.targetCount()
                    || !Set.of("PENDING", "IN_PROGRESS", "COMPLETED")
                            .contains(task.status())
                    || (task.completedCount() == 0 && !"PENDING".equals(task.status()))
                    || (task.completedCount() > 0
                            && task.completedCount() < task.targetCount()
                            && !"IN_PROGRESS".equals(task.status()))
                    || (task.completedCount() == task.targetCount()
                            && !"COMPLETED".equals(task.status()))
                    || task.dueOffsetDays() < 0) {
                throw new IllegalStateException("Initial plan task contract differs.");
            }
            probability(task.targetMastery(), "target mastery");
        }
        if (state.diagnosis().summary().isBlank()
                || state.diagnosis().strengths().isEmpty()
                || state.diagnosis().concerns().isEmpty()
                || state.diagnosis().recommendedActions().isEmpty()) {
            throw new IllegalStateException("Initial diagnosis content is incomplete.");
        }
    }

    private static String enrollmentKey(UUID courseId, UUID studentId) {
        return courseId + "|" + studentId;
    }

    private List<Map<String, Object>> dataVersionReferences(DemoSeedRegistry registry) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Dataset dataset : registry.datasets()) {
            LinkedHashMap<String, Object> reference = new LinkedHashMap<>();
            reference.put("sourceId", dataset.source().sourceKey());
            reference.put("version", dataset.version().versionId());
            reference.put("manifestSha256", dataset.version().manifestSha256());
            result.add(reference);
        }
        if (result.size() != 3) {
            throw new IllegalStateException("Bootstrap trace must contain three data versions.");
        }
        return result;
    }

    private List<Map<String, Object>> activeModelReferences(DemoSeedRegistry registry) {
        Map<String, Model> models = index(registry.models(), Model::versionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Deployment deployment : registry.deployments()) {
            Model model = models.get(deployment.activeVersionId());
            if (model == null) {
                throw new IllegalStateException("A bootstrap deployment has no model.");
            }
            Artifact artifact = model.artifacts().stream()
                    .min(Comparator.comparingInt(value -> artifactPriority(value.artifactRole())))
                    .orElseThrow();
            LinkedHashMap<String, Object> reference = new LinkedHashMap<>();
            reference.put("purpose", deployment.taskName());
            reference.put("family", model.modelFamily());
            reference.put("modelName", model.configJson().path("modelName")
                    .asText(model.modelFamily()));
            reference.put("modelVersion", model.versionId());
            reference.put("artifactSha256", artifact.sha256());
            reference.put("calibratorVersion", model.calibratorSha256() == null
                    ? "none" : "sha256:" + model.calibratorSha256());
            result.add(reference);
        }
        if (result.size() != 6) {
            throw new IllegalStateException("Bootstrap trace must contain six model purposes.");
        }
        return result;
    }

    private List<Map<String, Object>> effectiveModelReferences(
            List<Map<String, Object>> requestedModels) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> reference : requestedModels) {
            if (!"DIAGNOSIS".equals(reference.get("purpose"))) {
                result.add(new LinkedHashMap<>(reference));
                continue;
            }
            LinkedHashMap<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("purpose", "DIAGNOSIS");
            fallback.put("family", "TEMPLATE");
            fallback.put("modelName", TEMPLATE_DIAGNOSIS);
            fallback.put("modelVersion", TEMPLATE_DIAGNOSIS);
            fallback.put("artifactSha256", sha256Text(TEMPLATE_DIAGNOSIS));
            fallback.put("calibratorVersion", "none");
            result.add(fallback);
        }
        return result;
    }

    private static int artifactPriority(String role) {
        return switch (role) {
            case "SERVING_MODEL" -> 0;
            case "MODEL" -> 1;
            case "EXPLAINER" -> 2;
            case "RULE_CONFIG" -> 3;
            case "PROMPT_SCHEMA" -> 4;
            default -> 5;
        };
    }

    private static String dataVersion(List<Map<String, Object>> values, String sourceId) {
        return values.stream()
                .filter(value -> sourceId.equals(value.get("sourceId")))
                .map(value -> (String) value.get("version"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing bootstrap data version " + sourceId));
    }

    private void validateManifestEntry(JsonNode entry, Path path, String context)
            throws IOException {
        if (!entry.isObject()
                || !entry.path("path").asText().replace('\\', '/').endsWith(
                        "data/demo/generated/database/initial_states.jsonl")
                || !sha256(path).equals(entry.path("sha256").asText())
                || Files.size(path) != entry.path("bytes").asLong(-1)) {
            throw new IllegalStateException(context + " manifest entry differs.");
        }
    }

    private static void requireManifestReference(JsonNode entry, String context) {
        if (!entry.isObject()
                || !SHA256.matcher(entry.path("sha256").asText()).matches()
                || entry.path("bytes").asLong(-1) < 1
                || entry.path("path").asText().isBlank()) {
            throw new IllegalStateException(context + " manifest reference is invalid.");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Bootstrap projection JSON serialization failed.",
                    exception);
        }
    }

    private <T> void batch(
            String sql,
            List<T> rows,
            org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<T> setter) {
        jdbcTemplate.batchUpdate(sql, rows, BATCH_SIZE, setter);
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    private void requireCount(String context, long expected, String sql, Object... arguments) {
        long actual = count(sql, arguments);
        if (actual != expected) {
            throw new IllegalStateException(
                    "Persisted " + context + " count differs: " + actual + " != " + expected);
        }
    }

    private static void probability(BigDecimal value, String context) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(context + " must be in [0,1].");
        }
    }

    private static void requireText(String value, String context) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(context + " must not be blank.");
        }
    }

    private static <T, K> Set<K> unique(List<T> values, Function<T, K> key, String context) {
        LinkedHashSet<K> result = new LinkedHashSet<>();
        for (T value : values) {
            K item = key.apply(value);
            if (item == null || !result.add(item)) {
                throw new IllegalStateException("Duplicate or null " + context + ": " + item);
            }
        }
        return result;
    }

    private static <T, K> Map<K, T> index(List<T> values, Function<T, K> key) {
        LinkedHashMap<K, T> result = new LinkedHashMap<>();
        for (T value : values) {
            T previous = result.put(key.apply(value), value);
            if (previous != null) {
                throw new IllegalStateException("Duplicate bootstrap registry key.");
            }
        }
        return result;
    }

    private static Timestamp timestamp(OffsetDateTime value) {
        return Timestamp.from(value.withOffsetSameInstant(ZoneOffset.UTC).toInstant());
    }

    OffsetDateTime businessTime(OffsetDateTime value) {
        return businessTime.apply(value);
    }

    OffsetDateTime planScheduleTime(OffsetDateTime capturedAt, int snapshotVersion) {
        return snapshotVersion == SNAPSHOTS_PER_ENROLLMENT
                ? currentPlanAnchor
                : businessTime(capturedAt);
    }

    private static UUID stableId(String category, String... values) {
        StringBuilder payload = new StringBuilder(category);
        for (String value : values) {
            payload.append('|').append(value);
        }
        return UUID.nameUUIDFromBytes(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    record Seed(
            String manifestSha256,
            String statesSha256,
            List<InitialState> states,
            List<Map<String, Object>> dataVersions,
            List<Map<String, Object>> requestedModels,
            List<Map<String, Object>> effectiveModels,
            String assistmentsVersion,
            String ouladVersion) {}

    record InitialState(
            int schemaVersion,
            int snapshotVersion,
            UUID studentId,
            UUID courseId,
            UUID answerEventId,
            long eventSequence,
            OffsetDateTime capturedAt,
            KnowledgeState knowledge,
            RiskState risk,
            BigDecimal engagementScore,
            BigDecimal persistenceScore,
            BigDecimal planCompletionRate,
            PlanState plan,
            DiagnosisState diagnosis) {}

    record KnowledgeState(
            String masteryModelVersion,
            String nextModelVersion,
            String nextCalibratorVersion,
            BigDecimal nextCorrectProbability,
            List<SkillState> skills) {}

    record SkillState(
            UUID skillId,
            String skillName,
            BigDecimal probability) {}

    record RiskState(
            String modelVersion,
            String explainerModelVersion,
            String featureContractVersion,
            BigDecimal probability,
            String riskBand,
            BigDecimal baseValue,
            BigDecimal outputValue,
            boolean calibrated,
            Map<String, BigDecimal> features,
            List<RiskEvidence> evidence) {}

    record RiskEvidence(
            int rank,
            String featureName,
            BigDecimal rawValue,
            String direction,
            BigDecimal contribution,
            BigDecimal baseValue,
            String outputUnit) {}

    record PlanState(
            String ruleVersion,
            int validDays,
            List<PlanTask> tasks) {}

    record PlanTask(
            int ordinal,
            UUID questionId,
            UUID skillId,
            String taskType,
            String reasonCode,
            BigDecimal targetMastery,
            int targetCount,
            int completedCount,
            String status,
            int dueOffsetDays) {}

    record DiagnosisState(
            String effectiveModel,
            boolean toolCallVerified,
            String summary,
            List<String> strengths,
            List<String> concerns,
            List<String> recommendedActions) {}

    private record Projection(
            InitialState state,
            UUID jobId,
            UUID snapshotId,
            UUID correlationId,
            UUID planId,
            UUID diagnosisId,
            String dataVersions,
            String requestedModels,
            String effectiveModels) {}

    private record JobEvent(
            UUID id,
            UUID jobId,
            long sequence,
            String eventType,
            OffsetDateTime occurredAt) {}

    private record KnowledgePredictionRow(Projection projection, SkillState skill) {}

    private record SnapshotSkillRow(Projection projection, SkillState skill) {}

    private record PlanItemRow(Projection projection, PlanTask task, String title) {}

    private record EvidenceRow(Projection projection, RiskEvidence evidence) {}

    private record CourseRiskRow(
            UUID courseId,
            BigDecimal meanRisk,
            long lowCount,
            long mediumCount,
            long highCount,
            int studentCount,
            UUID sourceJobId,
            OffsetDateTime updatedAt) {}
}
