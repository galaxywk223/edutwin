package com.edutwin.analysis;

import com.edutwin.api.model.AnalysisFailure;
import com.edutwin.api.model.AnalysisJob;
import com.edutwin.api.model.AnalysisJobStatus;
import com.edutwin.api.model.AnalysisResultLinks;
import com.edutwin.api.model.AnalysisStage;
import com.edutwin.api.model.DataVersionRef;
import com.edutwin.api.model.ModelVersionRef;
import com.edutwin.api.model.TraceRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class AnalysisJobMapper implements RowMapper<AnalysisJob> {

    private static final TypeReference<LinkedHashSet<DataVersionRef>> DATA_VERSIONS =
            new TypeReference<>() {};
    private static final TypeReference<LinkedHashSet<ModelVersionRef>> MODEL_VERSIONS =
            new TypeReference<>() {};
    private static final TypeReference<LinkedHashSet<String>> STRING_SET =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public AnalysisJobMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AnalysisJob mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID jobId = uuid(resultSet, "id");
        UUID studentId = uuid(resultSet, "student_id");
        UUID courseId = uuid(resultSet, "course_id");
        AnalysisJobStatus status = AnalysisJobStatus.fromValue(resultSet.getString("status"));
        OffsetDateTime completedAt = timestamp(resultSet, "completed_at");

        Set<DataVersionRef> dataVersions = read(
                resultSet.getString("data_versions"), DATA_VERSIONS, "data_versions", jobId);
        Set<ModelVersionRef> requestedModels = read(
                resultSet.getString("requested_model_versions"),
                MODEL_VERSIONS,
                "requested_model_versions",
                jobId);
        Set<ModelVersionRef> effectiveModels = read(
                resultSet.getString("effective_model_versions"),
                MODEL_VERSIONS,
                "effective_model_versions",
                jobId);

        TraceRef trace = new TraceRef(
                uuid(resultSet, "correlation_id"),
                uuid(resultSet, "answer_event_id"),
                jobId,
                uuid(resultSet, "target_snapshot_id"),
                dataVersions,
                requestedModels,
                effectiveModels);

        Set<AnalysisJob.DegradationReasonsEnum> degradationReasons = new LinkedHashSet<>();
        String degradedJson = resultSet.getString("degraded_stages");
        if (degradedJson != null && !degradedJson.isBlank()) {
            for (String reason : readAllowEmpty(degradedJson, STRING_SET, "degraded_stages", jobId)) {
                degradationReasons.add(AnalysisJob.DegradationReasonsEnum.fromValue(reason));
            }
        }

        AnalysisFailure failure = null;
        String errorCode = resultSet.getString("error_code");
        if (errorCode != null) {
            failure = new AnalysisFailure(
                    errorCode,
                    resultSet.getString("error_message"),
                    resultSet.getBoolean("error_retryable"),
                    completedAt == null ? timestamp(resultSet, "created_at") : completedAt);
        }

        AnalysisResultLinks resultLinks = status == AnalysisJobStatus.COMPLETED
                ? new AnalysisResultLinks(
                        "/api/v1/courses/" + courseId + "/students/" + studentId + "/twin/current",
                        "/api/v1/courses/" + courseId + "/students/" + studentId
                                + "/learning-plans/current",
                        "/api/v1/courses/" + courseId + "/students/" + studentId + "/diagnosis")
                : new AnalysisResultLinks(null, null, null);

        return new AnalysisJob(
                jobId,
                studentId,
                courseId,
                status,
                AnalysisStage.fromValue(resultSet.getString("stage")),
                resultSet.getLong("last_event_sequence"),
                timestamp(resultSet, "created_at"),
                timestamp(resultSet, "started_at"),
                completedAt,
                failure,
                resultLinks)
                .trace(trace)
                .degraded(resultSet.getBoolean("degraded"))
                .degradationReasons(degradationReasons);
    }

    private <T> T read(String json, TypeReference<T> type, String column, UUID jobId)
            throws SQLException {
        T value = readAllowEmpty(json, type, column, jobId);
        if (value instanceof Set<?> set && set.isEmpty()) {
            throw new SQLException(
                    "Analysis job " + jobId + " contains an empty " + column + " trace value.");
        }
        return value;
    }

    private <T> T readAllowEmpty(String json, TypeReference<T> type, String column, UUID jobId)
            throws SQLException {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException(
                    "Analysis job " + jobId + " contains invalid " + column + " JSON.", exception);
        }
    }

    private static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return UUID.fromString(resultSet.getString(column));
    }

    private static OffsetDateTime timestamp(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
