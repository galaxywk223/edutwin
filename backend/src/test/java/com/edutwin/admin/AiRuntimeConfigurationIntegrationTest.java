package com.edutwin.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edutwin.ai.AiRuntimeConfigurationService;
import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class AiRuntimeConfigurationIntegrationTest {

    private static final UUID ACTOR_ID = UUID.fromString("c1000000-0000-0000-0000-000000000001");
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger();
    private static final AtomicBoolean REJECT = new AtomicBoolean();
    private static HttpServer server;
    private static String baseUrl;

    @Autowired private JdbcClient jdbc;
    @Autowired private AiRuntimeConfigurationService service;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", AiRuntimeConfigurationIntegrationTest::respond);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void seed() {
        cleanup();
        REQUEST_COUNT.set(0);
        REJECT.set(false);
        jdbc.sql("""
                INSERT INTO role_definition(code, description) VALUES ('ADMIN', 'Administrator')
                ON DUPLICATE KEY UPDATE description = VALUES(description)
                """).update();
        jdbc.sql("""
                        INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                        VALUES (:id, 'ai-config-admin', 'not-used', 'AI Config Admin', TRUE, 'ADMIN')
                        """)
                .param("id", ACTOR_ID.toString())
                .update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'ADMIN')")
                .param("id", ACTOR_ID.toString())
                .update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("UPDATE ai_runtime_configuration_state SET active_version_id = NULL, revision = 0, updated_by = NULL WHERE id = 1")
                .update();
        jdbc.sql("DELETE FROM admin_audit_event WHERE actor_user_id = :id")
                .param("id", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM ai_runtime_configuration_version WHERE created_by = :id")
                .param("id", ACTOR_ID.toString()).update();
        jdbc.sql("UPDATE user_account SET last_active_role = NULL WHERE id = :id")
                .param("id", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", ACTOR_ID.toString()).update();
    }

    @Test
    void activatesOnlyAfterARealToolCallAndNeverReturnsOrStoresPlaintext() {
        String apiKey = "sk-test-secret-1234";
        AdminDtos.AiConfigurationActivationResult result = service.activate(
                actor(), new AdminDtos.AiConfigurationUpdateRequest(
                        0, true, baseUrl, "test-model", apiKey));

        assertEquals("PASSED", result.testStatus());
        assertEquals("DATABASE", result.configuration().source());
        assertTrue(result.configuration().enabled());
        assertTrue(result.configuration().apiKeyConfigured());
        assertEquals(2, REQUEST_COUNT.get());
        assertEquals(1, result.configuration().revision());

        String ciphertext = jdbc.sql("SELECT HEX(api_key_ciphertext) FROM ai_runtime_configuration_version")
                .query(String.class).single();
        assertFalse(ciphertext.isBlank());
        assertNotEquals(apiKey, ciphertext);
        assertEquals(apiKey, service.current().apiKey());
        assertEquals(1L, jdbc.sql("""
                        SELECT COUNT(*) FROM admin_audit_event
                        WHERE action = 'AI_CONFIGURATION_ACTIVATED'
                          AND before_json NOT LIKE '%sk-test%'
                          AND after_json NOT LIKE '%sk-test%'
                        """).query(Long.class).single());
    }

    @Test
    void failedTestLeavesTheEnvironmentConfigurationActive() {
        REJECT.set(true);

        DomainException exception = assertThrows(DomainException.class, () -> service.activate(
                actor(), new AdminDtos.AiConfigurationUpdateRequest(
                        0, true, baseUrl, "test-model", "sk-rejected-secret")));

        assertEquals("AI_CONFIGURATION_TEST_FAILED", exception.code());
        assertEquals("ENVIRONMENT", service.view().source());
        assertEquals(0, service.view().revision());
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM ai_runtime_configuration_version")
                .query(Long.class).single());
    }

    @Test
    void retainsTheExistingKeyAndUsesOptimisticRevisionChecks() {
        AdminDtos.AiConfiguration first = service.activate(
                actor(), new AdminDtos.AiConfigurationUpdateRequest(
                        0, true, baseUrl, "test-model", "sk-retained-secret"))
                .configuration();
        REQUEST_COUNT.set(0);

        AdminDtos.AiConfiguration second = service.activate(
                actor(), new AdminDtos.AiConfigurationUpdateRequest(
                        first.revision(), true, baseUrl, "test-model-2", null))
                .configuration();

        assertEquals(2, REQUEST_COUNT.get());
        assertEquals("test-model-2", second.model());
        assertEquals("sk-retained-secret", service.current().apiKey());
        DomainException stale = assertThrows(DomainException.class, () -> service.activate(
                actor(), new AdminDtos.AiConfigurationUpdateRequest(
                        first.revision(), false, baseUrl, "test-model", null)));
        assertEquals("AI_CONFIGURATION_STALE", stale.code());
    }

    @Test
    void restoresTheDisabledEnvironmentConfigurationWithoutAnExternalCall() {
        AdminDtos.AiConfiguration active = service.activate(
                actor(), new AdminDtos.AiConfigurationUpdateRequest(
                        0, false, baseUrl, "test-model", "sk-stored-disabled"))
                .configuration();

        AdminDtos.AiConfigurationActivationResult restored = service.restoreEnvironment(
                actor(), new AdminDtos.AiConfigurationRestoreRequest(active.revision()));

        assertEquals("ENVIRONMENT", restored.configuration().source());
        assertFalse(restored.configuration().enabled());
        assertEquals("SKIPPED_DISABLED", restored.testStatus());
        assertEquals(0, REQUEST_COUNT.get());
    }

    private static EduTwinPrincipal actor() {
        return new EduTwinPrincipal(
                ACTOR_ID,
                "ai-config-admin",
                "AI Config Admin",
                "",
                UserRole.ADMIN,
                Set.of(UserRole.ADMIN),
                Set.of(),
                true,
                false,
                0);
    }

    private static void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            if (REJECT.get()) {
                byte[] body = "{\"error\":{\"message\":\"rejected\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            int request = REQUEST_COUNT.incrementAndGet();
            String json = request % 2 == 1
                    ? """
                      {"id":"test-1","object":"chat.completion","created":1,"model":"test-model",
                       "choices":[{"index":0,"message":{"role":"assistant","content":null,
                       "tool_calls":[{"id":"call-1","type":"function","function":{"name":"connectionProbe","arguments":"{}"}}]},
                       "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """
                    : """
                      {"id":"test-2","object":"chat.completion","created":2,"model":"test-model",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"OK"},
                       "finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
