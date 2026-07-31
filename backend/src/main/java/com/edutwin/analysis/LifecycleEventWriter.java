package com.edutwin.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class LifecycleEventWriter {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public LifecycleEventWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public UUID append(
            UUID jobId,
            long sequence,
            String eventType,
            ObjectNode eventPayload,
            Long snapshotVersion,
            UUID learningPlanId,
            Long learningPlanVersion) {
        EventContext context = loadContext(jobId, sequence);
        UUID eventId = UUID.randomUUID();
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();

        ObjectNode envelope = context.requestedEnvelope().deepCopy();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", now);
        envelope.put("producedAt", now);
        envelope.put("causationId", context.previousEventId().toString());
        envelope.put("producer", "analysis-worker");
        envelope.put("aggregateVersion", context.jobVersion());
        envelope.put("analysisJobVersion", context.jobVersion());
        envelope.put("snapshotId", context.snapshotId().toString());
        if (snapshotVersion == null) {
            envelope.putNull("snapshotVersion");
        } else {
            envelope.put("snapshotVersion", snapshotVersion);
        }
        if (learningPlanId == null) {
            envelope.putNull("learningPlanId");
            envelope.putNull("learningPlanVersion");
        } else {
            envelope.put("learningPlanId", learningPlanId.toString());
            envelope.put("learningPlanVersion", learningPlanVersion);
        }
        envelope.set("modelVersionIds", modelVersionIds(context.effectiveModelVersions()));
        envelope.set("payload", eventPayload);

        String json = json(envelope);
        jdbcClient.sql("""
                        INSERT INTO analysis_job_event(
                            id, analysis_job_id, sequence_no, event_type, payload)
                        VALUES (:id, :jobId, :sequence, :eventType, :payload)
                        """)
                .param("id", eventId.toString())
                .param("jobId", jobId.toString())
                .param("sequence", sequence)
                .param("eventType", eventType)
                .param("payload", json)
                .update();
        jdbcClient.sql("""
                        INSERT INTO outbox_event(
                            id, aggregate_type, aggregate_id, event_type,
                            schema_version, payload, status, attempts)
                        VALUES (
                            :id, 'ANALYSIS_JOB', :jobId, :eventType,
                            '1', :payload, 'PENDING', 0)
                        """)
                .param("id", eventId.toString())
                .param("jobId", jobId.toString())
                .param("eventType", eventType)
                .param("payload", json)
                .update();
        return eventId;
    }

    private EventContext loadContext(UUID jobId, long sequence) {
        if (sequence <= 1) {
            throw new IllegalArgumentException("Derived lifecycle sequences must be greater than one.");
        }
        String requestedJson = jdbcClient.sql("""
                        SELECT payload
                        FROM analysis_job_event
                        WHERE analysis_job_id = :jobId AND sequence_no = 1
                        """)
                .param("jobId", jobId.toString())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Analysis job " + jobId + " has no persisted request envelope."));
        UUID previousEventId = jdbcClient.sql("""
                        SELECT id
                        FROM analysis_job_event
                        WHERE analysis_job_id = :jobId AND sequence_no = :previousSequence
                        """)
                .param("jobId", jobId.toString())
                .param("previousSequence", sequence - 1)
                .query((resultSet, rowNumber) -> UUID.fromString(resultSet.getString("id")))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Analysis job " + jobId + " has a lifecycle sequence gap before " + sequence + "."));

        return jdbcClient.sql("""
                        SELECT target_snapshot_id, version, effective_model_versions
                        FROM analysis_job
                        WHERE id = :jobId
                        """)
                .param("jobId", jobId.toString())
                .query((resultSet, rowNumber) -> new EventContext(
                        objectNode(requestedJson),
                        previousEventId,
                        UUID.fromString(resultSet.getString("target_snapshot_id")),
                        resultSet.getLong("version"),
                        arrayNode(resultSet.getString("effective_model_versions"))))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Analysis job " + jobId + " does not exist."));
    }

    private ObjectNode modelVersionIds(JsonNode modelVersions) {
        ObjectNode result = objectMapper.createObjectNode();
        for (JsonNode model : modelVersions) {
            String purpose = model.path("purpose").asText();
            String version = model.path("modelVersion").asText();
            if (version.isBlank()) {
                throw new IllegalStateException("Effective model version is missing for " + purpose + ".");
            }
            result.put(asyncKey(purpose), version);
        }
        for (String required : new String[] {
            "mastery", "nextQuestion", "risk", "explainer", "planner", "diagnosis"
        }) {
            if (!result.hasNonNull(required)) {
                throw new IllegalStateException("Effective model mapping is missing " + required + ".");
            }
        }
        return result;
    }

    private static String asyncKey(String purpose) {
        return switch (purpose) {
            case "MASTERY" -> "mastery";
            case "NEXT_CORRECT" -> "nextQuestion";
            case "RISK" -> "risk";
            case "EXPLANATION" -> "explainer";
            case "PLAN_RULES" -> "planner";
            case "DIAGNOSIS" -> "diagnosis";
            default -> throw new IllegalStateException("Unsupported model purpose " + purpose + ".");
        };
    }

    private ObjectNode objectNode(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (!(parsed instanceof ObjectNode objectNode)) {
                throw new IllegalStateException("The request envelope is not a JSON object.");
            }
            return objectNode;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The request envelope is invalid JSON.", exception);
        }
    }

    private JsonNode arrayNode(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (!parsed.isArray() || parsed.isEmpty()) {
                throw new IllegalStateException("Effective model versions are not a non-empty array.");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Effective model versions are invalid JSON.", exception);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("A lifecycle event could not be serialized.", exception);
        }
    }

    private record EventContext(
            ObjectNode requestedEnvelope,
            UUID previousEventId,
            UUID snapshotId,
            long jobVersion,
            JsonNode effectiveModelVersions) {}
}
