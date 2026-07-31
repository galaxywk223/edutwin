package com.edutwin.analysis;

import com.edutwin.analysis.AnalysisWorkflowStore.DiagnosisContent;
import com.edutwin.analysis.AnalysisWorkflowStore.Failure;
import com.edutwin.analysis.AnalysisWorkflowStore.LeaseDecision;
import com.edutwin.analysis.AnalysisWorkflowStore.PlanResult;
import com.edutwin.analysis.AnalysisWorkflowStore.ProcessingLease;
import com.edutwin.analysis.AnalysisWorkflowStore.SnapshotResult;
import com.edutwin.analysis.AnalysisWorkItemRepository.WorkItem;
import com.edutwin.diagnosis.DeepSeekDiagnosisService;
import com.edutwin.shared.web.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AnalysisWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisWorker.class);

    private final StringRedisTemplate redisTemplate;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisWorkItemRepository workItemRepository;
    private final ModelInferenceService inferenceService;
    private final AnalysisWorkflowStore workflowStore;
    private final DeepSeekDiagnosisService diagnosisService;
    private final AnalysisJobProjectionService jobProjectionService;
    private final AnalysisStreamRecovery streamRecovery;
    private final AnalysisCacheCoordinator cacheCoordinator;
    private final TaskExecutor analysisTaskExecutor;
    private final String streamName;
    private final String consumerGroup;
    private final String consumerName;
    private final int maxAttempts;

    public AnalysisWorker(
            StringRedisTemplate redisTemplate,
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            AnalysisWorkItemRepository workItemRepository,
            ModelInferenceService inferenceService,
            AnalysisWorkflowStore workflowStore,
            DeepSeekDiagnosisService diagnosisService,
            AnalysisJobProjectionService jobProjectionService,
            AnalysisStreamRecovery streamRecovery,
            AnalysisCacheCoordinator cacheCoordinator,
            @Qualifier("analysisTaskExecutor") TaskExecutor analysisTaskExecutor,
            @Value("${edutwin.analysis.stream:analysis-events}") String streamName,
            @Value("${edutwin.analysis.consumer-group:edutwin-analysis-v1}") String consumerGroup,
            @Value("${edutwin.analysis.consumer-name:${HOSTNAME:local-backend}}") String consumerName,
            @Value("${edutwin.analysis.max-attempts:5}") int maxAttempts) {
        this.redisTemplate = redisTemplate;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.workItemRepository = workItemRepository;
        this.inferenceService = inferenceService;
        this.workflowStore = workflowStore;
        this.diagnosisService = diagnosisService;
        this.jobProjectionService = jobProjectionService;
        this.streamRecovery = streamRecovery;
        this.cacheCoordinator = cacheCoordinator;
        this.analysisTaskExecutor = analysisTaskExecutor;
        this.streamName = streamName;
        this.consumerGroup = consumerGroup;
        this.consumerName = consumerName;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${edutwin.analysis.worker-poll-delay:PT0.1S}")
    public void poll() {
        try {
            ensureGroup();
            handleBatch(streamRecovery.claimStale());
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().count(10).block(Duration.ofMillis(50)),
                    StreamOffset.create(streamName, ReadOffset.lastConsumed()));
            if (records == null) {
                return;
            }
            handleBatch(records);
        } catch (DataAccessException exception) {
            LOGGER.debug("Redis analysis polling is temporarily unavailable.", exception);
        }
    }

    private void handleBatch(List<MapRecord<String, Object, Object>> records) {
        if (records.isEmpty()) {
            return;
        }
        records.forEach(record -> analysisTaskExecutor.execute(() -> handle(record)));
    }

    private void handle(MapRecord<String, Object, Object> record) {
        String eventType;
        JsonNode envelope;
        UUID eventId;
        UUID jobId;
        UUID courseId;
        UUID studentId;
        try {
            eventType = field(record, "event_type");
            if (!"analysis.requested.v1".equals(eventType)) {
                acknowledge(record);
                return;
            }
            envelope = objectMapper.readTree(field(record, "payload"));
            eventId = UUID.fromString(envelope.path("eventId").asText());
            jobId = UUID.fromString(envelope.path("analysisJobId").asText());
            courseId = UUID.fromString(envelope.path("courseId").asText());
            studentId = UUID.fromString(envelope.path("studentId").asText());
            if (!eventId.toString().equals(field(record, "event_id"))) {
                throw new IllegalStateException("The Stream event identifier does not match its envelope.");
            }
        } catch (Exception exception) {
            LOGGER.error("Analysis Stream record {} is malformed and will be discarded.", record.getId(), exception);
            acknowledge(record);
            return;
        }

        ProcessingLease lease = null;
        String processingStage = null;
        try {
            if (alreadyConsumed(eventId)) {
                acknowledge(record);
                return;
            }

            lease = workflowStore.acquire(jobId);
            if (lease.decision() == LeaseDecision.DEFERRED) {
                return;
            }
            if (lease.decision() == LeaseDecision.TERMINAL) {
                recordConsumed(eventId, jobId);
                cacheCoordinator.refreshTerminalProjections(jobId, courseId, studentId);
                acknowledge(record);
                return;
            }
            jobProjectionService.evict(jobId);
            WorkItem workItem = workItemRepository.load(jobId);
            String stage = lease.stage();
            processingStage = stage;
            AnalysisInference inference = null;
            SnapshotResult snapshot = null;
            PlanResult plan = null;

            if ("MODEL_INFERENCE".equals(stage)) {
                inference = inferenceService.infer(workItem);
                workflowStore.persistPredictions(workItem, inference);
                stage = "SNAPSHOT_PERSISTENCE";
                processingStage = stage;
            }
            if ("SNAPSHOT_PERSISTENCE".equals(stage)) {
                if (inference == null) {
                    inference = restoreInference(workItem);
                }
                snapshot = workflowStore.persistSnapshot(workItem, inference);
                stage = "PLAN_GENERATION";
                processingStage = stage;
            }
            if ("PLAN_GENERATION".equals(stage)) {
                if (inference == null) {
                    inference = restoreInference(workItem);
                }
                if (snapshot == null) {
                    snapshot = workflowStore.loadSnapshotResult(jobId);
                }
                DiagnosisContent diagnosis = diagnosisService.diagnose(workItem, inference, snapshot);
                plan = workflowStore.persistPlanAndDiagnosis(
                        workItem, inference, snapshot, diagnosis);
                stage = "EVENT_PUBLICATION";
                processingStage = stage;
            }
            if (!"EVENT_PUBLICATION".equals(stage)) {
                throw new IllegalStateException("Analysis job has unsupported processing stage " + stage + ".");
            }
            if (snapshot == null) {
                snapshot = workflowStore.loadSnapshotResult(jobId);
            }
            if (plan == null) {
                plan = workflowStore.loadPlanResult(jobId);
            }
            workflowStore.complete(workItem, snapshot, plan);
            recordConsumed(eventId, jobId);
            cacheCoordinator.refreshTerminalProjections(jobId, courseId, studentId);
            acknowledge(record);
        } catch (Exception exception) {
            handleFailure(
                    record,
                    eventId,
                    jobId,
                    courseId,
                    studentId,
                    lease,
                    processingStage,
                    exception);
        }
    }

    private AnalysisInference restoreInference(WorkItem workItem) {
        AnalysisInference persisted = workflowStore.loadPersistedInference(workItem);
        AnalysisInference restored = inferenceService.restoreEvidence(workItem, persisted);
        workflowStore.synchronizeRecoveredInference(workItem.job().getJobId(), restored);
        return restored;
    }

    private void handleFailure(
            MapRecord<String, Object, Object> record,
            UUID eventId,
            UUID jobId,
            UUID courseId,
            UUID studentId,
            ProcessingLease lease,
            String processingStage,
            Exception exception) {
        if (lease == null || lease.decision() != LeaseDecision.ACQUIRED) {
            LOGGER.error("Analysis Stream record {} could not acquire its database lease.", record.getId(), exception);
            return;
        }

        String failedProcessingStage = processingStage == null ? lease.stage() : processingStage;
        Failure classified = classifyFailure(failedProcessingStage, exception);
        boolean exhausted = lease.attemptCount() >= maxAttempts;
        if (!classified.retryable() || exhausted) {
            Failure terminal = exhausted && classified.retryable()
                    ? new Failure(
                            failedStage(failedProcessingStage),
                            "ANALYSIS_RETRY_EXHAUSTED",
                            "Analysis processing exhausted the configured infrastructure retries.",
                            false)
                    : classified;
            if (workflowStore.fail(jobId, terminal, lease.attemptCount())) {
                recordConsumed(eventId, jobId);
                cacheCoordinator.refreshTerminalProjections(jobId, courseId, studentId);
                acknowledge(record);
            }
            LOGGER.error(
                    "Analysis Stream record {} reached terminal failure {}.",
                    record.getId(),
                    terminal.errorCode(),
                    exception);
            return;
        }

        workflowStore.releaseForRetry(jobId, lease.attemptCount());
        jobProjectionService.evict(jobId);
        LOGGER.warn(
                "Analysis Stream record {} will be retried after processing attempt {}.",
                record.getId(),
                lease.attemptCount(),
                exception);
    }

    private static Failure classifyFailure(String stage, Exception exception) {
        if (exception instanceof DataIntegrityViolationException) {
            return new Failure(
                    "PERSISTENCE",
                    "ANALYSIS_PERSISTENCE_CONSTRAINT",
                    "A persisted analysis invariant rejected the processing result.",
                    false);
        }
        if (exception instanceof IllegalArgumentException) {
            return new Failure(
                    "INVARIANT",
                    "ANALYSIS_INPUT_INVALID",
                    "The persisted analysis input is invalid.",
                    false);
        }
        if (exception instanceof DomainException) {
            return new Failure(
                    "INVARIANT",
                    "ANALYSIS_INPUT_MISSING",
                    "A required persisted analysis input is unavailable.",
                    false);
        }
        if (exception instanceof IllegalStateException) {
            String message = exception.getMessage() == null
                    ? ""
                    : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("sequence") || message.contains("current twin")) {
                return new Failure(
                        "ORDERING",
                        "ANALYSIS_ORDERING_CONFLICT",
                        "The answer sequence cannot be applied to the current twin state.",
                        false);
            }
            return new Failure(
                    "INVARIANT",
                    "ANALYSIS_INVARIANT_VIOLATION",
                    "A required persisted analysis invariant is not satisfied.",
                    false);
        }
        return new Failure(
                failedStage(stage),
                "ANALYSIS_INFRASTRUCTURE_FAILURE",
                "Analysis processing encountered a transient infrastructure failure.",
                true);
    }

    private static String failedStage(String stage) {
        return switch (stage) {
            case "MODEL_INFERENCE" -> "ANALYSIS";
            case "SNAPSHOT_PERSISTENCE" -> "SNAPSHOT";
            case "PLAN_GENERATION" -> "PLANNING";
            case "EVENT_PUBLICATION" -> "PERSISTENCE";
            default -> "INVARIANT";
        };
    }

    private boolean alreadyConsumed(UUID eventId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM consumed_message
                        WHERE consumer_group = :consumerGroup AND message_id = :eventId
                        """)
                .param("consumerGroup", consumerGroup)
                .param("eventId", eventId.toString())
                .query(Long.class)
                .single() > 0;
    }

    private void recordConsumed(UUID eventId, UUID jobId) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        INSERT IGNORE INTO consumed_message(
                            consumer_group, message_id, analysis_job_id)
                        VALUES (:consumerGroup, :eventId, :jobId)
                        """)
                .param("consumerGroup", consumerGroup)
                .param("eventId", eventId.toString())
                .param("jobId", jobId.toString())
                .update());
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        redisTemplate.opsForStream().acknowledge(streamName, consumerGroup, record.getId());
    }

    private void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(streamName, ReadOffset.from("0-0"), consumerGroup);
        } catch (DataAccessException exception) {
            if (!containsRedisError(exception, "BUSYGROUP")) {
                try {
                    redisTemplate.opsForStream().add(
                            org.springframework.data.redis.connection.stream.StreamRecords
                                    .newRecord()
                                    .ofMap(Map.of("bootstrap", "true"))
                                    .withStreamKey(streamName));
                    redisTemplate.opsForStream().createGroup(
                            streamName, ReadOffset.from("0-0"), consumerGroup);
                } catch (DataAccessException retry) {
                    if (!containsRedisError(retry, "BUSYGROUP")) {
                        throw retry;
                    }
                }
            }
        }
    }

    private static boolean containsRedisError(Throwable error, String token) {
        String normalizedToken = token.toUpperCase(Locale.ROOT);
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT).contains(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private static String field(MapRecord<String, Object, Object> record, String name) {
        Object value = record.getValue().get(name);
        if (value == null) {
            throw new IllegalStateException("Redis Stream record is missing field " + name + ".");
        }
        return value.toString();
    }
}
