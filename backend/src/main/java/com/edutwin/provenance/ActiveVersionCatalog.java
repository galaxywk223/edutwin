package com.edutwin.provenance;

import com.edutwin.api.model.DataVersionRef;
import com.edutwin.api.model.ModelVersionRef;
import com.edutwin.shared.web.DomainException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ActiveVersionCatalog {

    private static final List<ModelVersionRef.PurposeEnum> REQUIRED_PURPOSES = List.of(
            ModelVersionRef.PurposeEnum.MASTERY,
            ModelVersionRef.PurposeEnum.NEXT_CORRECT,
            ModelVersionRef.PurposeEnum.RISK,
            ModelVersionRef.PurposeEnum.EXPLANATION,
            ModelVersionRef.PurposeEnum.PLAN_RULES,
            ModelVersionRef.PurposeEnum.DIAGNOSIS);

    private static final String ACTIVE_MODEL_SQL = """
            SELECT md.task_name,
                   mv.version_id,
                   mv.model_family,
                   COALESCE(
                       NULLIF(JSON_UNQUOTE(JSON_EXTRACT(mv.config_json, '$.modelName')), 'null'),
                       mv.model_family) AS model_name,
                   mv.calibrator_sha256,
                   ma.sha256 AS artifact_sha256
            FROM model_deployment md
            JOIN model_version mv ON mv.version_id = md.active_version_id
            JOIN model_artifact ma ON ma.id = (
                SELECT candidate.id
                FROM model_artifact candidate
                WHERE candidate.model_version_id = mv.version_id
                ORDER BY CASE candidate.artifact_role
                    WHEN 'SERVING_MODEL' THEN 0
                    WHEN 'MODEL' THEN 1
                    WHEN 'EXPLAINER' THEN 2
                    WHEN 'RULE_CONFIG' THEN 3
                    WHEN 'PROMPT_SCHEMA' THEN 4
                    ELSE 5
                END,
                candidate.artifact_role,
                candidate.id
                LIMIT 1
            )
            WHERE md.task_name IN (
                'MASTERY', 'NEXT_CORRECT', 'RISK', 'EXPLANATION', 'PLAN_RULES', 'DIAGNOSIS')
              AND mv.status = 'ACTIVE'
            ORDER BY FIELD(
                md.task_name,
                'MASTERY', 'NEXT_CORRECT', 'RISK', 'EXPLANATION', 'PLAN_RULES', 'DIAGNOSIS')
            """;

    private final JdbcClient jdbcClient;

    public ActiveVersionCatalog(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Catalog loadForCourse(UUID courseId) {
        String fusionVersion = jdbcClient.sql("SELECT data_version FROM course WHERE id = :courseId")
                .param("courseId", courseId.toString())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "The requested course does not exist."));

        DataVersionRef assistments = dataVersionForDeployment(
                "MASTERY", DataVersionRef.SourceIdEnum.ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED);
        DataVersionRef oulad = dataVersionForDeployment("RISK", DataVersionRef.SourceIdEnum.OULAD);
        DataVersionRef fusion = dataVersionById(
                fusionVersion, DataVersionRef.SourceIdEnum.EDUTWIN_DEMO);

        LinkedHashSet<DataVersionRef> dataVersions = new LinkedHashSet<>(
                List.of(assistments, oulad, fusion));
        EnumMap<ModelVersionRef.PurposeEnum, ModelVersionRef> models = loadActiveModels();

        LinkedHashSet<ModelVersionRef> modelVersions = new LinkedHashSet<>();
        LinkedHashMap<String, String> modelVersionIds = new LinkedHashMap<>();
        for (ModelVersionRef.PurposeEnum purpose : REQUIRED_PURPOSES) {
            ModelVersionRef model = models.get(purpose);
            if (model == null) {
                throw incomplete("No active artifact-backed deployment exists for " + purpose.getValue() + ".");
            }
            modelVersions.add(model);
            modelVersionIds.put(asyncModelKey(purpose), model.getModelVersion());
        }

        LinkedHashMap<String, String> dataVersionIds = new LinkedHashMap<>();
        dataVersionIds.put("assistments", assistments.getVersion());
        dataVersionIds.put("oulad", oulad.getVersion());
        dataVersionIds.put("fusion", fusion.getVersion());

        return new Catalog(dataVersions, modelVersions, dataVersionIds, modelVersionIds);
    }

    private DataVersionRef dataVersionForDeployment(
            String taskName, DataVersionRef.SourceIdEnum expectedSource) {
        String sql = """
                SELECT dv.version_id, dv.manifest_sha256, ds.source_key
                FROM model_deployment md
                JOIN model_version mv ON mv.version_id = md.active_version_id
                JOIN dataset_version dv ON dv.version_id = mv.dataset_version_id
                JOIN dataset_source ds ON ds.id = dv.source_id
                WHERE md.task_name = :taskName
                  AND mv.status = 'ACTIVE'
                """;
        return jdbcClient.sql(sql)
                .param("taskName", taskName)
                .query((resultSet, rowNumber) -> toDataVersion(resultSet.getString("source_key"),
                        resultSet.getString("version_id"), resultSet.getString("manifest_sha256")))
                .optional()
                .filter(version -> version.getSourceId() == expectedSource)
                .orElseThrow(() -> incomplete(
                        "The active " + taskName + " deployment does not reference "
                                + expectedSource.getValue() + "."));
    }

    private DataVersionRef dataVersionById(
            String versionId, DataVersionRef.SourceIdEnum expectedSource) {
        String sql = """
                SELECT dv.version_id, dv.manifest_sha256, ds.source_key
                FROM dataset_version dv
                JOIN dataset_source ds ON ds.id = dv.source_id
                WHERE dv.version_id = :versionId
                """;
        return jdbcClient.sql(sql)
                .param("versionId", versionId)
                .query((resultSet, rowNumber) -> toDataVersion(resultSet.getString("source_key"),
                        resultSet.getString("version_id"), resultSet.getString("manifest_sha256")))
                .optional()
                .filter(version -> version.getSourceId() == expectedSource)
                .orElseThrow(() -> incomplete(
                        "Course data version " + versionId + " is not an EDUTWIN_DEMO dataset version."));
    }

    private EnumMap<ModelVersionRef.PurposeEnum, ModelVersionRef> loadActiveModels() {
        EnumMap<ModelVersionRef.PurposeEnum, ModelVersionRef> result =
                new EnumMap<>(ModelVersionRef.PurposeEnum.class);
        jdbcClient.sql(ACTIVE_MODEL_SQL)
                .query((resultSet, rowNumber) -> {
                    ModelVersionRef.PurposeEnum purpose =
                            ModelVersionRef.PurposeEnum.fromValue(resultSet.getString("task_name"));
                    String calibratorSha = resultSet.getString("calibrator_sha256");
                    String calibratorVersion = calibratorSha == null
                            ? "none"
                            : "sha256:" + calibratorSha;
                    ModelVersionRef ref = new ModelVersionRef(
                            purpose,
                            ModelVersionRef.FamilyEnum.fromValue(resultSet.getString("model_family")),
                            resultSet.getString("model_name"),
                            resultSet.getString("version_id"),
                            resultSet.getString("artifact_sha256"),
                            calibratorVersion);
                    return Map.entry(purpose, ref);
                })
                .list()
                .forEach(entry -> {
                    if (result.put(entry.getKey(), entry.getValue()) != null) {
                        throw incomplete("Multiple active deployments exist for " + entry.getKey().getValue() + ".");
                    }
                });
        return result;
    }

    private static DataVersionRef toDataVersion(
            String sourceKey, String versionId, String manifestSha256) {
        try {
            return new DataVersionRef(
                    DataVersionRef.SourceIdEnum.fromValue(sourceKey), versionId, manifestSha256);
        } catch (IllegalArgumentException exception) {
            throw incomplete("Unsupported dataset source key: " + sourceKey + ".");
        }
    }

    private static String asyncModelKey(ModelVersionRef.PurposeEnum purpose) {
        return switch (purpose) {
            case MASTERY -> "mastery";
            case NEXT_CORRECT -> "nextQuestion";
            case RISK -> "risk";
            case EXPLANATION -> "explainer";
            case PLAN_RULES -> "planner";
            case DIAGNOSIS -> "diagnosis";
        };
    }

    private static DomainException incomplete(String detail) {
        return new DomainException(HttpStatus.CONFLICT, "VERSION_CATALOG_INCOMPLETE", detail);
    }

    public record Catalog(
            Set<DataVersionRef> dataVersions,
            Set<ModelVersionRef> modelVersions,
            Map<String, String> dataVersionIds,
            Map<String, String> modelVersionIds) {

        public Catalog {
            dataVersions = Collections.unmodifiableSet(new LinkedHashSet<>(dataVersions));
            modelVersions = Collections.unmodifiableSet(new LinkedHashSet<>(modelVersions));
            dataVersionIds = Collections.unmodifiableMap(new LinkedHashMap<>(dataVersionIds));
            modelVersionIds = Collections.unmodifiableMap(new LinkedHashMap<>(modelVersionIds));
        }
    }
}
