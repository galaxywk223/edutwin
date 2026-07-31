package com.edutwin.provenance;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.edutwin.api.model.TransparencyResponse;
import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=${EDUTWIN_DB_URL:"
            + "jdbc:mysql://127.0.0.1:33306/edutwin_test?useUnicode=true"
            + "&characterEncoding=utf8&serverTimezone=UTC"
            + "&allowPublicKeyRetrieval=true&useSSL=false}",
    "spring.datasource.username=${EDUTWIN_DB_USER:edutwin}",
    "spring.datasource.password=${EDUTWIN_DB_PASSWORD:edutwin-local}",
    "edutwin.ai.enabled=false"
})
@AutoConfigureMockMvc
class TransparencyIntegrationTest {

    private static final String SHA_PATTERN = "^[a-f0-9]{64}$";
    private static final UUID PROCESSING_RUN_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000001");
    private static final UUID ASSISTMENTS_SOURCE_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000010");
    private static final UUID OULAD_SOURCE_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000011");
    private static final UUID DEMO_SOURCE_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000012");
    private static final String ASSISTMENTS_VERSION = "transparency-assist-v1";
    private static final String OULAD_VERSION = "transparency-oulad-v1";
    private static final String DEMO_VERSION = "transparency-demo-match-v1";

    private static final List<ModelFixture> MODELS = List.of(
            new ModelFixture("IRT", "NEXT_CORRECT", "transparency-irt-v1", false, '1', "AUC"),
            new ModelFixture("BKT", "MASTERY", "transparency-bkt-v1", true, '2', "AUC"),
            new ModelFixture("DKT", "NEXT_CORRECT", "transparency-dkt-v1", false, '3', "AUC"),
            new ModelFixture("AKT", "NEXT_CORRECT", "transparency-akt-v1", true, '4', "AUC"),
            new ModelFixture(
                    "LOGISTIC_REGRESSION",
                    "RISK",
                    "transparency-logistic-v1",
                    false,
                    '5',
                    "PR_AUC"),
            new ModelFixture(
                    "LIGHTGBM",
                    "RISK",
                    "transparency-lightgbm-v1",
                    true,
                    '6',
                    "PR_AUC"),
            new ModelFixture(
                    "CATBOOST",
                    "RISK",
                    "transparency-catboost-v1",
                    false,
                    '7',
                    "PR_AUC"));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransparencyReadService service;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private Validator validator;

    @BeforeEach
    void seedCatalog() {
        requireTestDatabase();
        cleanup();
        insertProcessingRun();
        insertSource(
                ASSISTMENTS_SOURCE_ID,
                "ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED",
                "ASSISTments 2009-2010 Skill Builder");
        insertSource(OULAD_SOURCE_ID, "OULAD", "Open University Learning Analytics Dataset");
        insertSource(DEMO_SOURCE_ID, "EDUTWIN_DEMO", "EduTwin synthetic matching dataset");
        insertDatasetVersion(ASSISTMENTS_VERSION, ASSISTMENTS_SOURCE_ID, 'a', 401_756L);
        insertDatasetVersion(OULAD_VERSION, OULAD_SOURCE_ID, 'b', 32_593L);
        insertDatasetVersion(DEMO_VERSION, DEMO_SOURCE_ID, 'c', 2_000L);
        MODELS.forEach(this::insertModel);
    }

    @AfterEach
    void removeCatalog() {
        requireTestDatabase();
        cleanup();
    }

    @Test
    void administratorReceivesContractValidDeterministicCatalog() throws Exception {
        assertCatalog(adminAuthentication());

        TransparencyResponse response = service.get();
        assertTrue(
                validator.validate(response).isEmpty(),
                () -> "transparency response violates generated contract: "
                        + validator.validate(response));
    }

    @Test
    void missingAuthenticationReturnsContractProblem() throws Exception {
        mockMvc.perform(get("/api/v1/admin/transparency"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void incompleteCandidateSetReturnsNotReadyInsteadOfPartialCatalog() throws Exception {
        ModelFixture missing = MODELS.get(MODELS.size() - 1);
        deleteModel(missing);

        mockMvc.perform(get("/api/v1/admin/transparency")
                        .with(authentication(adminAuthentication())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TRANSPARENCY_CATALOG_NOT_READY"))
                .andExpect(jsonPath("$.detail").value(matchesPattern(".*CATBOOST.*")));
    }

    private void assertCatalog(Authentication authentication) throws Exception {
        mockMvc.perform(get("/api/v1/admin/transparency").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.provenance.synthetic").value(true))
                .andExpect(jsonPath("$.provenance.matchingVersion").value(DEMO_VERSION))
                .andExpect(jsonPath("$.provenance.matchingSeed").value(42))
                .andExpect(jsonPath("$.provenance.generatedAt").isNotEmpty())
                .andExpect(jsonPath("$.provenance.sources", hasSize(2)))
                .andExpect(jsonPath("$.provenance.sources[*].sourceId", contains(
                        "ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED", "OULAD")))
                .andExpect(jsonPath("$.provenance.sources[*].datasetVersion", contains(
                        ASSISTMENTS_VERSION, OULAD_VERSION)))
                .andExpect(jsonPath("$.provenance.sources[*].rowCount", contains(401_756, 32_593)))
                .andExpect(jsonPath("$.provenance.sources[*].splitSeed", contains(42, 42)))
                .andExpect(jsonPath("$.models", hasSize(7)))
                .andExpect(jsonPath("$.models[*].model.family", contains(
                        "IRT",
                        "BKT",
                        "DKT",
                        "AKT",
                        "LOGISTIC_REGRESSION",
                        "LIGHTGBM",
                        "CATBOOST")))
                .andExpect(jsonPath("$.models[*].selected", contains(
                        false, true, false, true, false, true, false)))
                .andExpect(jsonPath("$.models[*].configSha256", everyItem(matchesPattern(SHA_PATTERN))))
                .andExpect(jsonPath(
                        "$.models[*].dependencyLockSha256",
                        everyItem(matchesPattern(SHA_PATTERN))))
                .andExpect(jsonPath("$.models[*].metrics[0].split", everyItem(
                        org.hamcrest.Matchers.is("VALIDATION"))))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty())
                .andExpect(jsonPath("$..studentId").doesNotExist())
                .andExpect(jsonPath("$..answerEventId").doesNotExist())
                .andExpect(jsonPath("$..analysisJobId").doesNotExist())
                .andExpect(jsonPath("$..snapshotId").doesNotExist());
    }

    private void insertProcessingRun() {
        jdbcClient.sql("""
                        INSERT INTO processing_run(
                            id, pipeline_version, git_tree_sha256, config_sha256,
                            random_seed, status, started_at, completed_at,
                            output_manifest_sha256)
                        VALUES (
                            :id, 'transparency-pipeline-v1', :sha, :sha, 42,
                            'SUCCEEDED', '2026-07-11 01:00:00.000000',
                            '2026-07-11 01:30:00.000000', :sha)
                        """)
                .param("id", PROCESSING_RUN_ID.toString())
                .param("sha", sha('d'))
                .update();
    }

    private void insertSource(UUID id, String sourceKey, String name) {
        jdbcClient.sql("""
                        INSERT INTO dataset_source(
                            id, source_key, name, official_url,
                            license_name, license_url, citation_text)
                        VALUES (
                            :id, :sourceKey, :name, :officialUrl,
                            :licenseName, :licenseUrl, :citation)
                        """)
                .param("id", id.toString())
                .param("sourceKey", sourceKey)
                .param("name", name)
                .param("officialUrl", "https://example.test/source/" + sourceKey.toLowerCase())
                .param("licenseName", "Fixture terms for " + sourceKey)
                .param("licenseUrl", "https://example.test/license/" + sourceKey.toLowerCase())
                .param("citation", "Transparency integration fixture")
                .update();
    }

    private void insertDatasetVersion(
            String version, UUID sourceId, char hashCharacter, long rowCount) {
        jdbcClient.sql("""
                        INSERT INTO dataset_version(
                            version_id, source_id, source_sha256, schema_sha256,
                            processing_config_sha256, manifest_sha256,
                            processing_run_id, row_count, processed_at, manifest_json)
                        VALUES (
                            :version, :sourceId, :sha, :sha, :sha, :sha,
                            :runId, :rowCount, '2026-07-11 01:30:00.000000',
                            JSON_OBJECT('fixture', TRUE))
                        """)
                .param("version", version)
                .param("sourceId", sourceId.toString())
                .param("sha", sha(hashCharacter))
                .param("runId", PROCESSING_RUN_ID.toString())
                .param("rowCount", rowCount)
                .update();
    }

    private void insertModel(ModelFixture model) {
        String datasetVersion = switch (model.family()) {
            case "IRT", "BKT", "DKT", "AKT" -> ASSISTMENTS_VERSION;
            default -> OULAD_VERSION;
        };
        jdbcClient.sql("""
                        INSERT INTO model_version(
                            version_id, model_family, task_name, dataset_version_id,
                            status, random_seed, config_json, feature_contract_sha256,
                            calibrator_type, calibrator_sha256, manifest_sha256,
                            selected, frozen_at, test_evaluated_at, created_at)
                        VALUES (
                            :version, :family, :task, :datasetVersion,
                            'FROZEN', 42,
                            JSON_OBJECT('seed', 42, 'modelName', :modelName), :sha,
                            'PLATT', :sha, :sha, :selected,
                            '2026-07-11 02:00:00.000000', NULL,
                            '2026-07-11 02:00:00.000000')
                        """)
                .param("version", model.version())
                .param("family", model.family())
                .param("task", model.task())
                .param("datasetVersion", datasetVersion)
                .param("modelName", "fixture-" + model.family().toLowerCase())
                .param("sha", sha(model.hashCharacter()))
                .param("selected", model.selected())
                .update();
        UUID artifactId = UUID.nameUUIDFromBytes(
                ("transparency-artifact:" + model.version()).getBytes(StandardCharsets.UTF_8));
        jdbcClient.sql("""
                        INSERT INTO model_artifact(
                            id, model_version_id, artifact_role, artifact_uri,
                            sha256, size_bytes, dependency_versions)
                        VALUES (
                            :id, :version, 'SERVING_MODEL', :uri, :sha, 512,
                            JSON_OBJECT('python', '3.11.13', 'runtime', 'cpu'))
                        """)
                .param("id", artifactId.toString())
                .param("version", model.version())
                .param("uri", "artifact://" + model.version())
                .param("sha", sha(model.hashCharacter()))
                .update();
        jdbcClient.sql("""
                        INSERT INTO model_metric(
                            model_version_id, split_name, metric_name,
                            metric_value, measured_at)
                        VALUES (
                            :version, 'validation', :metric, :value,
                            '2026-07-11 02:00:00.000000')
                        """)
                .param("version", model.version())
                .param("metric", model.metric())
                .param("value", new BigDecimal("0.750000000000"))
                .update();
    }

    private void cleanup() {
        redisTemplate.delete("transparency:current");
        for (ModelFixture model : MODELS) {
            deleteModel(model);
        }
        for (String version : List.of(ASSISTMENTS_VERSION, OULAD_VERSION, DEMO_VERSION)) {
            jdbcClient.sql("DELETE FROM dataset_version WHERE version_id = :version")
                    .param("version", version)
                    .update();
        }
        for (UUID sourceId : List.of(
                ASSISTMENTS_SOURCE_ID, OULAD_SOURCE_ID, DEMO_SOURCE_ID)) {
            jdbcClient.sql("DELETE FROM dataset_source WHERE id = :id")
                    .param("id", sourceId.toString())
                    .update();
        }
        jdbcClient.sql("DELETE FROM processing_run WHERE id = :id")
                .param("id", PROCESSING_RUN_ID.toString())
                .update();
    }

    private void deleteModel(ModelFixture model) {
        jdbcClient.sql("DELETE FROM model_metric WHERE model_version_id = :version")
                .param("version", model.version())
                .update();
        jdbcClient.sql("DELETE FROM model_artifact WHERE model_version_id = :version")
                .param("version", model.version())
                .update();
        jdbcClient.sql("DELETE FROM model_version WHERE version_id = :version")
                .param("version", model.version())
                .update();
    }

    private Authentication studentAuthentication() {
        return authenticationFor(
                UUID.fromString("73000000-0000-0000-0000-000000000100"),
                UserRole.STUDENT);
    }

    private Authentication teacherAuthentication() {
        return authenticationFor(
                UUID.fromString("73000000-0000-0000-0000-000000000101"),
                UserRole.TEACHER);
    }

    @Test
    void studentAndTeacherCannotReadAdministratorCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/admin/transparency").with(authentication(studentAuthentication())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/transparency").with(authentication(teacherAuthentication())))
                .andExpect(status().isForbidden());
    }

    private Authentication adminAuthentication() {
        return authenticationFor(
                UUID.fromString("73000000-0000-0000-0000-000000000102"),
                UserRole.ADMIN);
    }

    private Authentication authenticationFor(UUID id, UserRole role) {
        EduTwinPrincipal principal = new EduTwinPrincipal(
                id,
                "transparency-" + role.getValue().toLowerCase(),
                "Transparency " + role.getValue(),
                "",
                role,
                Set.of(),
                true);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, "", principal.getAuthorities());
    }

    private void requireTestDatabase() {
        String database = jdbcClient.sql("SELECT DATABASE()").query(String.class).single();
        if (!"edutwin_test".equals(database)) {
            throw new IllegalStateException(
                    "TransparencyIntegrationTest requires edutwin_test, got " + database);
        }
    }

    private static String sha(char character) {
        return String.valueOf(character).repeat(64);
    }

    private record ModelFixture(
            String family,
            String task,
            String version,
            boolean selected,
            char hashCharacter,
            String metric) {}
}
