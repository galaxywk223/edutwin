package com.edutwin.provenance;

import com.edutwin.api.model.DataProvenance;
import com.edutwin.api.model.MetricValue;
import com.edutwin.api.model.ModelTransparency;
import com.edutwin.api.model.ModelVersionRef;
import com.edutwin.api.model.SourceProvenance;
import com.edutwin.api.model.TransparencyResponse;
import com.edutwin.shared.web.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransparencyReadService {

    private static final String CACHE_KEY = "transparency:current";

    private static final Set<ModelVersionRef.FamilyEnum> REQUIRED_CANDIDATE_FAMILIES =
            EnumSet.of(
                    ModelVersionRef.FamilyEnum.IRT,
                    ModelVersionRef.FamilyEnum.BKT,
                    ModelVersionRef.FamilyEnum.DKT,
                    ModelVersionRef.FamilyEnum.AKT,
                    ModelVersionRef.FamilyEnum.LOGISTIC_REGRESSION,
                    ModelVersionRef.FamilyEnum.LIGHTGBM,
                    ModelVersionRef.FamilyEnum.CATBOOST);

    private static final String SOURCE_VERSION_SQL = """
            SELECT ds.source_key, ds.official_url, ds.license_name, ds.license_url,
                   dv.version_id, dv.source_sha256, dv.manifest_sha256,
                   dv.processing_config_sha256, dv.processing_run_id, dv.row_count,
                   dv.processed_at, pr.random_seed, mv.selected, mv.status,
                   COALESCE(mv.frozen_at, mv.created_at) AS model_order_at
            FROM dataset_source ds
            JOIN dataset_version dv ON dv.source_id = ds.id
            JOIN processing_run pr ON pr.id = dv.processing_run_id
            JOIN model_version mv ON mv.dataset_version_id = dv.version_id
            WHERE ds.source_key IN (
                    'ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED', 'OULAD')
              AND pr.status = 'SUCCEEDED'
              AND mv.model_family IN (
                    'IRT', 'BKT', 'DKT', 'AKT',
                    'LOGISTIC_REGRESSION', 'LIGHTGBM', 'CATBOOST')
            ORDER BY FIELD(
                        ds.source_key,
                        'ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED', 'OULAD'),
                     mv.selected DESC,
                     FIELD(mv.status, 'ACTIVE', 'FROZEN', 'CANDIDATE', 'ROLLED_BACK'),
                     model_order_at DESC,
                     dv.processed_at DESC,
                     dv.version_id DESC
            """;

    private static final String MODEL_SQL = """
            SELECT mv.version_id, mv.model_family, mv.task_name, mv.config_json,
                   mv.calibrator_sha256, mv.manifest_sha256, mv.selected,
                   ma.sha256 AS artifact_sha256,
                   ma.dependency_versions
            FROM model_version mv
            JOIN model_artifact ma ON ma.id = (
                SELECT candidate.id
                FROM model_artifact candidate
                WHERE candidate.model_version_id = mv.version_id
                ORDER BY CASE candidate.artifact_role
                    WHEN 'SERVING_MODEL' THEN 0
                    WHEN 'MODEL' THEN 1
                    ELSE 2
                END,
                candidate.artifact_role,
                candidate.id
                LIMIT 1
            )
            WHERE ((mv.dataset_version_id = :assistmentsVersion
                    AND mv.model_family IN ('IRT', 'BKT', 'DKT', 'AKT'))
                OR (mv.dataset_version_id = :ouladVersion
                    AND mv.model_family IN (
                        'LOGISTIC_REGRESSION', 'LIGHTGBM', 'CATBOOST')))
              AND mv.status IN ('CANDIDATE', 'FROZEN', 'ACTIVE', 'ROLLED_BACK')
            ORDER BY FIELD(
                         mv.model_family,
                         'IRT', 'BKT', 'DKT', 'AKT',
                         'LOGISTIC_REGRESSION', 'LIGHTGBM', 'CATBOOST'),
                     mv.version_id
            """;

    private static final String METRIC_SQL = """
            SELECT split_name, metric_name, metric_value
            FROM model_metric
            WHERE model_version_id = :modelVersion
              AND LOWER(split_name) IN ('validation', 'test')
              AND UPPER(metric_name) IN (
                    'AUC', 'LOG_LOSS', 'ECE_15', 'CPU_P95_MS',
                    'PR_AUC', 'BRIER_SCORE')
            ORDER BY FIELD(LOWER(split_name), 'validation', 'test'),
                     FIELD(
                         UPPER(metric_name),
                         'AUC', 'PR_AUC', 'LOG_LOSS', 'BRIER_SCORE',
                         'ECE_15', 'CPU_P95_MS')
            """;

    private static final String MATCHING_SQL = """
            SELECT dv.version_id, dv.processed_at, pr.random_seed
            FROM dataset_version dv
            JOIN dataset_source ds ON ds.id = dv.source_id
            JOIN processing_run pr ON pr.id = dv.processing_run_id
            WHERE ds.source_key = 'EDUTWIN_DEMO'
              AND pr.status = 'SUCCEEDED'
            ORDER BY dv.processed_at DESC, dv.version_id DESC
            LIMIT 1
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper canonicalJsonMapper;
    private final StringRedisTemplate redisTemplate;
    private final Object buildLock = new Object();

    public TransparencyReadService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate) {
        this.jdbcClient = jdbcClient;
        this.redisTemplate = redisTemplate;
        this.canonicalJsonMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional(readOnly = true)
    public TransparencyResponse get() {
        TransparencyResponse cached = readCached();
        if (cached != null) {
            return cached;
        }
        synchronized (buildLock) {
            cached = readCached();
            if (cached != null) {
                return cached;
            }
            TransparencyResponse response = build();
            writeCached(response);
            return response;
        }
    }

    private TransparencyResponse build() {
        Map<SourceProvenance.SourceIdEnum, SourceRow> sources = loadSourceVersions();
        SourceRow assistments = requireSource(
                sources, SourceProvenance.SourceIdEnum.ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED);
        SourceRow oulad = requireSource(sources, SourceProvenance.SourceIdEnum.OULAD);
        MatchingRow matching = loadMatching();

        List<ModelTransparency> models = loadModels(assistments.version(), oulad.version());
        requireCandidateFamilies(models);

        List<SourceProvenance> sourceResponses = List.of(
                toSourceProvenance(assistments), toSourceProvenance(oulad));
        DataProvenance provenance = new DataProvenance(
                true,
                matching.version(),
                DataProvenance.MatchingSeedEnum.NUMBER_42,
                matching.generatedAt(),
                sourceResponses);
        return new TransparencyResponse(
                provenance, models, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private TransparencyResponse readCached() {
        try {
            String value = redisTemplate.opsForValue().get(CACHE_KEY);
            return value == null
                    ? null
                    : canonicalJsonMapper.readValue(value, TransparencyResponse.class);
        } catch (DataAccessException | JsonProcessingException ignored) {
            return null;
        }
    }

    private void writeCached(TransparencyResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    CACHE_KEY,
                    canonicalJsonMapper.writeValueAsString(response),
                    Duration.ofMinutes(10));
        } catch (DataAccessException | JsonProcessingException ignored) {
            // MySQL remains authoritative when the cache is unavailable.
        }
    }

    private Map<SourceProvenance.SourceIdEnum, SourceRow> loadSourceVersions() {
        LinkedHashMap<SourceProvenance.SourceIdEnum, SourceRow> result = new LinkedHashMap<>();
        jdbcClient.sql(SOURCE_VERSION_SQL)
                .query((resultSet, rowNumber) -> {
                    SourceProvenance.SourceIdEnum sourceId = sourceId(
                            resultSet.getString("source_key"));
                    return new SourceRow(
                            sourceId,
                            resultSet.getString("version_id"),
                            resultSet.getString("source_sha256"),
                            resultSet.getString("manifest_sha256"),
                            resultSet.getString("license_name"),
                            resultSet.getString("license_url"),
                            resultSet.getString("official_url"),
                            resultSet.getLong("row_count"),
                            UUID.fromString(resultSet.getString("processing_run_id")),
                            resultSet.getString("processing_config_sha256"),
                            resultSet.getInt("random_seed"));
                })
                .list()
                .forEach(row -> result.putIfAbsent(row.sourceId(), row));
        return result;
    }

    private List<ModelTransparency> loadModels(
            String assistmentsVersion, String ouladVersion) {
        List<ModelRow> rows = jdbcClient.sql(MODEL_SQL)
                .param("assistmentsVersion", assistmentsVersion)
                .param("ouladVersion", ouladVersion)
                .query((resultSet, rowNumber) -> {
                    String version = resultSet.getString("version_id");
                    return new ModelRow(
                            version,
                            resultSet.getString("model_family"),
                            resultSet.getString("task_name"),
                            resultSet.getString("config_json"),
                            resultSet.getString("calibrator_sha256"),
                            resultSet.getString("manifest_sha256"),
                            resultSet.getBoolean("selected"),
                            resultSet.getString("artifact_sha256"),
                            resultSet.getString("dependency_versions"));
                })
                .list();
        return rows.stream().map(this::toModelTransparency).toList();
    }

    private ModelTransparency toModelTransparency(ModelRow row) {
        ModelVersionRef.FamilyEnum family = modelFamily(row.family(), row.version());
        ModelVersionRef.PurposeEnum purpose = modelPurpose(row.task(), row.version());
        String modelName = modelName(row.configJson(), family, row.version());
        ModelVersionRef model = new ModelVersionRef(
                purpose,
                family,
                modelName,
                row.version(),
                requireSha(row.artifactSha(), "artifact SHA-256", row.version()),
                row.calibratorSha() == null
                        ? "none"
                        : "sha256:" + requireSha(
                                row.calibratorSha(), "calibrator SHA-256", row.version()));
        List<MetricValue> metrics = loadMetrics(row.version());
        if (metrics.isEmpty()) {
            throw notReady("Model " + row.version()
                    + " has no contract-compatible validation or test metrics.");
        }
        return new ModelTransparency(
                model,
                requireSha(row.manifestSha(), "manifest SHA-256", row.version()),
                canonicalSha(row.configJson(), "config_json", row.version()),
                canonicalSha(
                        row.dependenciesJson(), "dependency_versions", row.version()),
                row.selected(),
                metrics);
    }

    private List<MetricValue> loadMetrics(String version) {
        LinkedHashMap<String, MetricValue> result = new LinkedHashMap<>();
        jdbcClient.sql(METRIC_SQL)
                .param("modelVersion", version)
                .query((resultSet, rowNumber) -> {
                    MetricValue.NameEnum name = metricName(
                            resultSet.getString("metric_name"), version);
                    MetricValue.SplitEnum split = metricSplit(
                            resultSet.getString("split_name").toUpperCase(Locale.ROOT), version);
                    return new MetricValue(name, split, resultSet.getBigDecimal("metric_value"));
                })
                .list()
                .forEach(metric -> result.putIfAbsent(
                        metric.getSplit().getValue() + ":" + metric.getName().getValue(), metric));
        return List.copyOf(result.values());
    }

    private MatchingRow loadMatching() {
        MatchingRow matching = jdbcClient.sql(MATCHING_SQL)
                .query((resultSet, rowNumber) -> new MatchingRow(
                        resultSet.getString("version_id"),
                        offset(resultSet.getTimestamp("processed_at")),
                        resultSet.getInt("random_seed")))
                .optional()
                .orElseThrow(() -> notReady(
                        "No successful EDUTWIN_DEMO matching dataset is available."));
        if (matching.seed() != 42) {
            throw notReady("The active matching dataset was not produced with seed 42.");
        }
        return matching;
    }

    private SourceProvenance toSourceProvenance(SourceRow row) {
        return new SourceProvenance(
                row.sourceId(),
                row.version(),
                requireSha(row.sourceSha(), "source SHA-256", row.version()),
                requireSha(row.manifestSha(), "manifest SHA-256", row.version()),
                requireText(row.licenseName(), "license name", row.version()),
                uri(row.licenseUrl(), "license URL", row.version()),
                uri(row.sourceUrl(), "source URL", row.version()),
                row.rowCount(),
                row.processingRunId(),
                requireSha(row.processingConfigSha(),
                        "processing config SHA-256", row.version()),
                SourceProvenance.SplitSeedEnum.NUMBER_42);
    }

    private static SourceRow requireSource(
            Map<SourceProvenance.SourceIdEnum, SourceRow> sources,
            SourceProvenance.SourceIdEnum sourceId) {
        SourceRow row = sources.get(sourceId);
        if (row == null) {
            throw notReady("No successful candidate-backed dataset version exists for "
                    + sourceId.getValue() + ".");
        }
        if (row.splitSeed() != 42) {
            throw notReady("Dataset " + row.version()
                    + " was not produced with split seed 42.");
        }
        return row;
    }

    private static void requireCandidateFamilies(List<ModelTransparency> models) {
        Set<ModelVersionRef.FamilyEnum> actual = EnumSet.noneOf(ModelVersionRef.FamilyEnum.class);
        models.forEach(model -> actual.add(model.getModel().getFamily()));
        if (!actual.containsAll(REQUIRED_CANDIDATE_FAMILIES)) {
            Set<ModelVersionRef.FamilyEnum> missing = EnumSet.copyOf(REQUIRED_CANDIDATE_FAMILIES);
            missing.removeAll(actual);
            throw notReady("The transparency catalog is missing candidate families: " + missing + ".");
        }
    }

    private String modelName(
            String configJson, ModelVersionRef.FamilyEnum family, String version) {
        try {
            Object value = canonicalJsonMapper.readValue(configJson, Object.class);
            if (!(value instanceof Map<?, ?> map)) {
                throw notReady("Model " + version + " has a non-object config_json value.");
            }
            Object configured = map.get("modelName");
            if (configured instanceof String text && !text.isBlank()) {
                return text;
            }
            return family.getValue();
        } catch (JsonProcessingException exception) {
            throw notReady("Model " + version + " has invalid config_json.");
        }
    }

    private String canonicalSha(String json, String field, String version) {
        try {
            Object parsed = canonicalJsonMapper.readValue(json, Object.class);
            byte[] canonical = canonicalJsonMapper.writeValueAsBytes(parsed);
            return sha256(canonical);
        } catch (JsonProcessingException exception) {
            throw notReady("Model " + version + " has invalid " + field + ".");
        }
    }

    private static String requireSha(String value, String field, String version) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw notReady("Model or dataset " + version + " has an invalid " + field + ".");
        }
        return value;
    }

    private static String requireText(String value, String field, String version) {
        if (value == null || value.isBlank()) {
            throw notReady("Dataset " + version + " has an empty " + field + ".");
        }
        return value;
    }

    private static URI uri(String value, String field, String version) {
        try {
            URI uri = new URI(requireText(value, field, version));
            if (!uri.isAbsolute()) {
                throw new URISyntaxException(value, "URI is not absolute");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw notReady("Dataset " + version + " has an invalid " + field + ".");
        }
    }

    private static SourceProvenance.SourceIdEnum sourceId(String value) {
        try {
            return SourceProvenance.SourceIdEnum.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw notReady("Unsupported transparency source: " + value + ".");
        }
    }

    private static ModelVersionRef.FamilyEnum modelFamily(String value, String version) {
        try {
            return ModelVersionRef.FamilyEnum.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw notReady("Model " + version + " has an unsupported family: " + value + ".");
        }
    }

    private static ModelVersionRef.PurposeEnum modelPurpose(String value, String version) {
        try {
            return ModelVersionRef.PurposeEnum.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw notReady("Model " + version + " has an unsupported task: " + value + ".");
        }
    }

    private static MetricValue.NameEnum metricName(String value, String version) {
        try {
            return MetricValue.NameEnum.fromValue(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw notReady("Model " + version + " has an unsupported metric: " + value + ".");
        }
    }

    private static MetricValue.SplitEnum metricSplit(String value, String version) {
        try {
            return MetricValue.SplitEnum.fromValue(value);
        } catch (IllegalArgumentException exception) {
            throw notReady("Model " + version + " has an unsupported metric split: " + value + ".");
        }
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static DomainException notReady(String detail) {
        return new DomainException(
                HttpStatus.NOT_FOUND, "TRANSPARENCY_CATALOG_NOT_READY", detail);
    }

    private record SourceRow(
            SourceProvenance.SourceIdEnum sourceId,
            String version,
            String sourceSha,
            String manifestSha,
            String licenseName,
            String licenseUrl,
            String sourceUrl,
            long rowCount,
            UUID processingRunId,
            String processingConfigSha,
            int splitSeed) {}

    private record MatchingRow(
            String version, OffsetDateTime generatedAt, int seed) {}

    private record ModelRow(
            String version,
            String family,
            String task,
            String configJson,
            String calibratorSha,
            String manifestSha,
            boolean selected,
            String artifactSha,
            String dependenciesJson) {}
}
