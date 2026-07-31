package com.edutwin.twin;

import com.edutwin.api.model.AnalysisJob;
import com.edutwin.api.model.RiskBand;
import com.edutwin.api.model.RiskPrediction;
import com.edutwin.api.model.SkillMastery;
import com.edutwin.api.model.TraceRef;
import com.edutwin.api.model.TwinHistory;
import com.edutwin.api.model.TwinState;
import com.edutwin.shared.web.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TwinReadService {

    private static final TypeReference<LinkedHashSet<AnalysisJob.DegradationReasonsEnum>> DEGRADATIONS =
            new TypeReference<>() {};

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public TwinReadService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    public TwinState current(UUID courseId, UUID studentId) {
        String key = "twin:" + studentId + ":current";
        try {
            String cached = redisTemplate.opsForHash().get(key, courseId.toString()) instanceof String value
                    ? value
                    : null;
            if (cached != null) {
                return objectMapper.readValue(cached, TwinState.class);
            }
        } catch (DataAccessException | JsonProcessingException ignored) {
            // MySQL remains authoritative.
        }
        TwinState persisted = queryStates(courseId, studentId, null, 1).stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND,
                        "TWIN_SNAPSHOT_NOT_FOUND",
                        "No twin snapshot exists for the requested student and course."));
        try {
            redisTemplate.opsForHash().put(
                    key, courseId.toString(), objectMapper.writeValueAsString(persisted));
            redisTemplate.expire(key, java.time.Duration.ofMinutes(15));
        } catch (DataAccessException | JsonProcessingException ignored) {
            // Cache write failures do not change the result.
        }
        return persisted;
    }

    public TwinHistory history(
            UUID courseId, UUID studentId, Long beforeVersion, int limit) {
        List<TwinState> items = queryStates(courseId, studentId, beforeVersion, limit);
        int total = jdbcClient.sql("""
                        SELECT COUNT(*) FROM twin_snapshot
                        WHERE course_id = :courseId AND student_id = :studentId
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .query(Integer.class)
                .single();
        Long next = items.size() == limit
                ? items.get(items.size() - 1).getSnapshotVersion()
                : null;
        return new TwinHistory(items, total, next);
    }

    private List<TwinState> queryStates(
            UUID courseId, UUID studentId, Long beforeVersion, int limit) {
        String versionClause = beforeVersion == null
                ? ""
                : " AND ts.snapshot_version < :beforeVersion";
        var query = jdbcClient.sql("""
                        SELECT ts.*, aj.correlation_id, aj.data_versions,
                               aj.requested_model_versions, aj.effective_model_versions,
                               aj.degraded_stages, rp.calibrated, rp.risk_band,
                               rp.base_value, rp.created_at AS predicted_at
                        FROM twin_snapshot ts
                        JOIN analysis_job aj ON aj.id = ts.analysis_job_id
                        JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                        WHERE ts.course_id = :courseId AND ts.student_id = :studentId
                        """ + versionClause + """
                        ORDER BY ts.snapshot_version DESC
                        LIMIT :limit
                        """)
                .param("courseId", courseId.toString())
                .param("studentId", studentId.toString())
                .param("limit", limit);
        if (beforeVersion != null) {
            query = query.param("beforeVersion", beforeVersion);
        }
        return query.query((resultSet, rowNumber) -> {
                    UUID snapshotId = UUID.fromString(resultSet.getString("id"));
                    TraceRef trace = new TraceRef(
                            UUID.fromString(resultSet.getString("correlation_id")),
                            UUID.fromString(resultSet.getString("answer_event_id")),
                            UUID.fromString(resultSet.getString("analysis_job_id")),
                            snapshotId,
                            readSet(resultSet.getString("data_versions"),
                                    com.edutwin.api.model.DataVersionRef.class),
                            readSet(resultSet.getString("requested_model_versions"),
                                    com.edutwin.api.model.ModelVersionRef.class),
                            readSet(resultSet.getString("effective_model_versions"),
                                    com.edutwin.api.model.ModelVersionRef.class));
                    List<SkillMastery> mastery = skillMastery(snapshotId);
                    RiskPrediction risk = new RiskPrediction(
                            resultSet.getBigDecimal("risk_probability"),
                            RiskBand.fromValue(resultSet.getString("risk_band")),
                            new BigDecimal("0.35000000"),
                            new BigDecimal("0.65000000"),
                            offset(resultSet.getTimestamp("predicted_at")))
                            .trace(trace)
                            .calibrated(resultSet.getBoolean("calibrated"))
                            .baseValue(resultSet.getBigDecimal("base_value"))
                            .featureContractVersion("oulad-d0-29-v1")
                            .degraded(resultSet.getBoolean("degraded"));
                    Set<TwinState.DegradationReasonsEnum> degradationReasons =
                            new LinkedHashSet<>();
                    for (AnalysisJob.DegradationReasonsEnum value : readDegradations(
                            resultSet.getString("degraded_stages"))) {
                        switch (value) {
                            case KNOWLEDGE_MODEL_UNAVAILABLE,
                                    RISK_MODEL_UNAVAILABLE,
                                    MODEL_TIMEOUT,
                                    MODEL_CONTRACT_ERROR -> degradationReasons.add(
                                            TwinState.DegradationReasonsEnum.fromValue(
                                                    value.getValue()));
                            case DEEPSEEK_UNAVAILABLE, DEEPSEEK_TIMEOUT,
                                    LLM_UNAVAILABLE, LLM_TIMEOUT, REDIS_RETRY -> {
                                // These stages do not change the persisted twin prediction.
                            }
                        }
                    }
                    return new TwinState(
                            studentId,
                            courseId,
                            resultSet.getLong("snapshot_version"),
                            offset(resultSet.getTimestamp("created_at")),
                            mastery,
                            resultSet.getBigDecimal("engagement_score"),
                            resultSet.getBigDecimal("persistence_score"),
                            resultSet.getBigDecimal("next_correct_probability"),
                            risk,
                            resultSet.getBigDecimal("plan_completion_rate"))
                            .trace(trace)
                            .degraded(resultSet.getBoolean("degraded"))
                            .degradationReasons(degradationReasons);
                })
                .list();
    }

    private List<SkillMastery> skillMastery(UUID snapshotId) {
        return jdbcClient.sql("""
                        SELECT tss.skill_id, ks.name, tss.mastery_probability,
                               tss.mastery_model_version
                        FROM twin_snapshot_skill tss
                        JOIN knowledge_skill ks ON ks.id = tss.skill_id
                        WHERE tss.snapshot_id = :snapshotId
                        ORDER BY ks.name, tss.skill_id
                        """)
                .param("snapshotId", snapshotId.toString())
                .query((resultSet, rowNumber) -> new SkillMastery(
                        UUID.fromString(resultSet.getString("skill_id")),
                        resultSet.getString("name"),
                        resultSet.getBigDecimal("mastery_probability"))
                        .estimator(resultSet.getString("mastery_model_version").startsWith("rule-")
                                ? SkillMastery.EstimatorEnum.RULE_FALLBACK
                                : SkillMastery.EstimatorEnum.BKT))
                .list();
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

    private Set<AnalysisJob.DegradationReasonsEnum> readDegradations(String json) {
        try {
            return objectMapper.readValue(json, DEGRADATIONS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted degradation JSON is invalid.", exception);
        }
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
