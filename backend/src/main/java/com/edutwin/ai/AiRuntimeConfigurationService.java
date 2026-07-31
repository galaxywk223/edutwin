package com.edutwin.ai;

import com.edutwin.admin.AdminDtos;
import com.edutwin.admin.BusinessAuditService;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiRuntimeConfigurationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRuntimeConfigurationService.class);
    private static final String DISABLED_KEY = "disabled";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final BusinessAuditService audit;
    private final TransactionTemplate transaction;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, ChatClient> clients = new ConcurrentHashMap<>();
    private final Snapshot environment;
    private final SecretKey encryptionKey;
    private final Duration testTimeout;
    private final boolean allowInsecureBaseUrl;

    public AiRuntimeConfigurationService(
            JdbcClient jdbc,
            BusinessAuditService audit,
            PlatformTransactionManager transactionManager,
            @Value("${edutwin.ai.enabled:false}") boolean environmentEnabled,
            @Value("${spring.ai.openai.base-url:https://api.deepseek.com}") String environmentBaseUrl,
            @Value("${spring.ai.openai.api-key:disabled}") String environmentApiKey,
            @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash}") String environmentModel,
            @Value("${edutwin.ai.configuration-encryption-key:}") String encryptionKeyValue,
            @Value("${edutwin.ai.configuration-test-timeout:PT20S}") Duration testTimeout,
            @Value("${edutwin.ai.allow-insecure-base-url:false}") boolean allowInsecureBaseUrl) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.testTimeout = testTimeout;
        this.allowInsecureBaseUrl = allowInsecureBaseUrl;
        this.encryptionKey = encryptionKey(encryptionKeyValue);
        this.environment = new Snapshot(
                null,
                environmentEnabled,
                normalizeBaseUrl(environmentBaseUrl),
                normalizedModel(environmentModel),
                configuredKey(environmentApiKey) ? environmentApiKey : null,
                "environment");
    }

    public AdminDtos.AiConfiguration view() {
        StateRow row = stateRow(false);
        if (row.activeVersionId() == null) {
            return new AdminDtos.AiConfiguration(
                    "ENVIRONMENT",
                    row.revision(),
                    environment.enabled(),
                    environment.apiBaseUrl(),
                    environment.model(),
                    environment.apiKey() != null,
                    encryptionKey != null,
                    environment.enabled() ? "NOT_TESTED" : "SKIPPED_DISABLED",
                    null,
                    null,
                    row.updatedAt(),
                    row.updatedBy());
        }
        return new AdminDtos.AiConfiguration(
                "DATABASE",
                row.revision(),
                row.enabled(),
                row.apiBaseUrl(),
                row.model(),
                row.apiKeyConfigured(),
                encryptionKey != null,
                row.lastTestStatus(),
                row.lastTestedAt(),
                row.lastTestLatencyMs(),
                row.updatedAt(),
                row.updatedBy());
    }

    public AdminDtos.AiConfigurationActivationResult activate(
            EduTwinPrincipal actor, AdminDtos.AiConfigurationUpdateRequest request) {
        requireEncryptionKey();
        StateRow initial = stateRow(false);
        requireRevision(initial.revision(), request.expectedRevision());

        Snapshot current = current();
        String apiKey = request.apiKey() == null || request.apiKey().isBlank()
                ? current.apiKey()
                : normalizedApiKey(request.apiKey());
        Snapshot candidate = new Snapshot(
                null,
                request.enabled(),
                normalizeBaseUrl(request.apiBaseUrl()),
                normalizedModel(request.model()),
                apiKey,
                "candidate");
        if (candidate.enabled() && candidate.apiKey() == null) {
            throw badRequest("AI_API_KEY_REQUIRED", "An API key is required before AI can be enabled.");
        }

        TestResult test = candidate.enabled()
                ? testConnection(candidate)
                : new TestResult("SKIPPED_DISABLED", null, null);
        UUID versionId = UUID.randomUUID();
        EncryptedSecret encrypted = encrypt(candidate.apiKey(), versionId);

        transaction.executeWithoutResult(status -> {
            StateRow locked = stateRow(true);
            requireRevision(locked.revision(), request.expectedRevision());
            jdbc.sql("""
                            INSERT INTO ai_runtime_configuration_version(
                                id, enabled, api_base_url, model_name,
                                api_key_ciphertext, api_key_iv, api_key_fingerprint,
                                last_test_status, last_tested_at, last_test_latency_ms, created_by)
                            VALUES (
                                :id, :enabled, :baseUrl, :model,
                                :ciphertext, :iv, :fingerprint,
                                :testStatus, :testedAt, :latencyMs, :createdBy)
                            """)
                    .param("id", versionId.toString())
                    .param("enabled", candidate.enabled())
                    .param("baseUrl", candidate.apiBaseUrl())
                    .param("model", candidate.model())
                    .param("ciphertext", encrypted == null ? null : encrypted.ciphertext())
                    .param("iv", encrypted == null ? null : encrypted.iv())
                    .param("fingerprint", encrypted == null ? null : encrypted.fingerprint())
                    .param("testStatus", test.status())
                    .param("testedAt", test.testedAt() == null ? null : Timestamp.from(test.testedAt()))
                    .param("latencyMs", test.latencyMs())
                    .param("createdBy", actor.userId().toString())
                    .update();
            jdbc.sql("""
                            UPDATE ai_runtime_configuration_state
                            SET active_version_id = :versionId,
                                revision = revision + 1,
                                updated_by = :updatedBy
                            WHERE id = 1 AND revision = :expectedRevision
                            """)
                    .param("versionId", versionId.toString())
                    .param("updatedBy", actor.userId().toString())
                    .param("expectedRevision", request.expectedRevision())
                    .update();
        });

        AdminDtos.AiConfiguration after = view();
        audit.record(
                actor,
                "AI_CONFIGURATION_ACTIVATED",
                "AI_CONFIGURATION",
                "global",
                "SUCCEEDED",
                null,
                null,
                auditView(initial),
                auditView(after),
                Map.of("apiKeyChanged", !fingerprint(current.apiKey()).equals(fingerprint(candidate.apiKey()))));
        return new AdminDtos.AiConfigurationActivationResult(after, test.status(), test.latencyMs());
    }

    public AdminDtos.AiConfigurationActivationResult restoreEnvironment(
            EduTwinPrincipal actor, AdminDtos.AiConfigurationRestoreRequest request) {
        StateRow initial = stateRow(false);
        requireRevision(initial.revision(), request.expectedRevision());
        TestResult test = environment.enabled()
                ? testConnection(environment)
                : new TestResult("SKIPPED_DISABLED", null, null);
        transaction.executeWithoutResult(status -> {
            StateRow locked = stateRow(true);
            requireRevision(locked.revision(), request.expectedRevision());
            jdbc.sql("""
                            UPDATE ai_runtime_configuration_state
                            SET active_version_id = NULL,
                                revision = revision + 1,
                                updated_by = :updatedBy
                            WHERE id = 1 AND revision = :expectedRevision
                            """)
                    .param("updatedBy", actor.userId().toString())
                    .param("expectedRevision", request.expectedRevision())
                    .update();
        });
        AdminDtos.AiConfiguration after = view();
        audit.record(
                actor,
                "AI_CONFIGURATION_RESTORED",
                "AI_CONFIGURATION",
                "global",
                "SUCCEEDED",
                null,
                null,
                auditView(initial),
                auditView(after),
                Map.of("source", "ENVIRONMENT"));
        return new AdminDtos.AiConfigurationActivationResult(after, test.status(), test.latencyMs());
    }

    public Snapshot current() {
        UUID activeVersionId = stateRow(false).activeVersionId();
        return activeVersionId == null ? environment : configuration(activeVersionId);
    }

    public UUID currentVersionId() {
        return stateRow(false).activeVersionId();
    }

    public Snapshot configuration(UUID versionId) {
        if (versionId == null) return environment;
        return jdbc.sql("""
                        SELECT enabled, api_base_url, model_name,
                               api_key_ciphertext, api_key_iv
                        FROM ai_runtime_configuration_version
                        WHERE id = :id
                        """)
                .param("id", versionId.toString())
                .query((resultSet, rowNumber) -> new Snapshot(
                        versionId,
                        resultSet.getBoolean("enabled"),
                        resultSet.getString("api_base_url"),
                        resultSet.getString("model_name"),
                        decrypt(
                                resultSet.getBytes("api_key_ciphertext"),
                                resultSet.getBytes("api_key_iv"),
                                versionId),
                        "database:" + versionId))
                .optional()
                .orElseThrow(() -> new IllegalStateException("The requested AI configuration version does not exist."));
    }

    public ChatClient client(Snapshot configuration) {
        if (!configuration.enabled() || configuration.apiKey() == null) {
            throw new IllegalStateException("The AI runtime configuration is disabled or incomplete.");
        }
        String cacheKey = configuration.reference()
                + ":" + configuration.apiBaseUrl()
                + ":" + configuration.model()
                + ":" + fingerprint(configuration.apiKey());
        return clients.computeIfAbsent(cacheKey, ignored -> buildClient(configuration));
    }

    private TestResult testConnection(Snapshot configuration) {
        ProbeTool probe = new ProbeTool();
        Instant startedAt = Instant.now();
        Future<?> future = executor.submit(() -> client(configuration)
                .prompt()
                .system("Call the connectionProbe tool exactly once, then answer with OK.")
                .user("Verify this OpenAI-compatible model connection.")
                .tools(probe)
                .call()
                .content());
        try {
            future.get(testTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!probe.called()) {
                throw new DomainException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "AI_TOOL_CALL_UNSUPPORTED",
                        "The configured model did not complete the required tool call.");
            }
            long latency = Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
            return new TestResult("PASSED", latency, Instant.now());
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new DomainException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "AI_CONFIGURATION_TEST_TIMEOUT",
                    "The configured model connection test timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The AI configuration test was interrupted.", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof DomainException domainException) throw domainException;
            throw new DomainException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "AI_CONFIGURATION_TEST_FAILED",
                    "The configured API address, key, model, or tool-call capability could not be verified.");
        }
    }

    private ChatClient buildClient(Snapshot configuration) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(configuration.apiBaseUrl())
                .apiKey(configuration.apiKey())
                .completionsPath("/chat/completions")
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(configuration.model())
                .temperature(0.1)
                .build();
        return ChatClient.create(OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build());
    }

    private StateRow stateRow(boolean forUpdate) {
        return jdbc.sql("""
                        SELECT s.revision, s.active_version_id, s.updated_at,
                               actor.display_name AS updated_by,
                               v.enabled, v.api_base_url, v.model_name,
                               v.api_key_fingerprint, v.last_test_status,
                               v.last_tested_at, v.last_test_latency_ms
                        FROM ai_runtime_configuration_state s
                        LEFT JOIN ai_runtime_configuration_version v ON v.id = s.active_version_id
                        LEFT JOIN user_account actor ON actor.id = s.updated_by
                        WHERE s.id = 1
                        """ + (forUpdate ? " FOR UPDATE" : ""))
                .query((resultSet, rowNumber) -> new StateRow(
                        resultSet.getLong("revision"),
                        resultSet.getString("active_version_id") == null
                                ? null
                                : UUID.fromString(resultSet.getString("active_version_id")),
                        resultSet.getObject("enabled") == null ? null : resultSet.getBoolean("enabled"),
                        resultSet.getString("api_base_url"),
                        resultSet.getString("model_name"),
                        resultSet.getString("api_key_fingerprint") != null,
                        resultSet.getString("last_test_status"),
                        offset(resultSet.getTimestamp("last_tested_at")),
                        resultSet.getObject("last_test_latency_ms") == null
                                ? null
                                : resultSet.getLong("last_test_latency_ms"),
                        offset(resultSet.getTimestamp("updated_at")),
                        resultSet.getString("updated_by")))
                .single();
    }

    private Map<String, Object> auditView(StateRow row) {
        return Map.of(
                "source", row.activeVersionId() == null ? "ENVIRONMENT" : "DATABASE",
                "revision", row.revision(),
                "enabled", row.activeVersionId() == null ? environment.enabled() : Boolean.TRUE.equals(row.enabled()),
                "apiBaseUrl", row.activeVersionId() == null ? environment.apiBaseUrl() : row.apiBaseUrl(),
                "model", row.activeVersionId() == null ? environment.model() : row.model(),
                "apiKeyConfigured", row.activeVersionId() == null
                        ? environment.apiKey() != null
                        : row.apiKeyConfigured());
    }

    private static Map<String, Object> auditView(AdminDtos.AiConfiguration value) {
        return Map.of(
                "source", value.source(),
                "revision", value.revision(),
                "enabled", value.enabled(),
                "apiBaseUrl", value.apiBaseUrl(),
                "model", value.model(),
                "apiKeyConfigured", value.apiKeyConfigured());
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 500) {
            throw badRequest("AI_BASE_URL_INVALID", "A valid API base URL is required.");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw badRequest("AI_BASE_URL_INVALID", "A valid API base URL is required.");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw badRequest("AI_BASE_URL_INVALID", "The API base URL must be an absolute host URL without credentials or query data.");
        }
        if (!uri.getScheme().equalsIgnoreCase("https")
                && !(allowInsecureBaseUrl && uri.getScheme().equalsIgnoreCase("http"))) {
            throw badRequest("AI_BASE_URL_INSECURE", "The API base URL must use HTTPS.");
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        return normalized;
    }

    private static String normalizedModel(String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || !value.matches("[A-Za-z0-9._:/-]+")) {
            throw badRequest("AI_MODEL_INVALID", "A valid OpenAI-compatible model name is required.");
        }
        return value.trim();
    }

    private static String normalizedApiKey(String value) {
        String normalized = value.trim();
        if (normalized.length() < 8 || normalized.length() > 512 || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw badRequest("AI_API_KEY_INVALID", "The API key format is invalid.");
        }
        return normalized;
    }

    private static boolean configuredKey(String value) {
        return value != null && !value.isBlank() && !DISABLED_KEY.equalsIgnoreCase(value.trim());
    }

    private EncryptedSecret encrypt(String value, UUID versionId) {
        if (value == null) return null;
        requireEncryptionKey();
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(versionId.toString().getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(
                    cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),
                    iv,
                    fingerprint(value));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AI configuration encryption failed.", exception);
        }
    }

    private String decrypt(byte[] ciphertext, byte[] iv, UUID versionId) {
        if (ciphertext == null || iv == null) return null;
        requireEncryptionKey();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(versionId.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("The active AI configuration could not be decrypted.", exception);
        }
    }

    private void requireEncryptionKey() {
        if (encryptionKey == null) {
            throw new DomainException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_CONFIGURATION_ENCRYPTION_UNAVAILABLE",
                    "The AI configuration encryption key is not available.");
        }
    }

    private static SecretKey encryptionKey(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            if (decoded.length != 32) throw new IllegalArgumentException("invalid length");
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("EDUTWIN_AI_CONFIG_ENCRYPTION_KEY is invalid; runtime AI configuration writes are disabled.");
            return null;
        }
    }

    private static String fingerprint(String value) {
        if (value == null) return "none";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void requireRevision(long actual, long expected) {
        if (actual != expected) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "AI_CONFIGURATION_STALE",
                    "The AI configuration changed after this page was loaded.");
        }
    }

    private static OffsetDateTime offset(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static DomainException badRequest(String code, String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, detail);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    public record Snapshot(
            UUID versionId,
            boolean enabled,
            String apiBaseUrl,
            String model,
            String apiKey,
            String reference) {}

    private record StateRow(
            long revision,
            UUID activeVersionId,
            Boolean enabled,
            String apiBaseUrl,
            String model,
            boolean apiKeyConfigured,
            String lastTestStatus,
            OffsetDateTime lastTestedAt,
            Long lastTestLatencyMs,
            OffsetDateTime updatedAt,
            String updatedBy) {}

    private record TestResult(String status, Long latencyMs, Instant testedAt) {}
    private record EncryptedSecret(byte[] ciphertext, byte[] iv, String fingerprint) {}

    public static final class ProbeTool {
        private volatile boolean called;

        @org.springframework.ai.tool.annotation.Tool(
                name = "connectionProbe",
                description = "Return a fixed token proving that tool calling works.")
        public String connectionProbe() {
            called = true;
            return "EDUTWIN_AI_CONNECTION_OK";
        }

        boolean called() {
            return called;
        }
    }
}
