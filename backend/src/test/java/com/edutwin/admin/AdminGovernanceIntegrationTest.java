package com.edutwin.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AdminGovernanceIntegrationTest {

    private static final UUID ACTOR_ID = uuid("b1000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_ID = uuid("b1000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = uuid("b1000000-0000-0000-0000-000000000003");
    private static final String RETIRE_VERSION = "governance-retire-v1";
    private static final String MODEL_DATASET = "governance-model-data-v1";
    private static final String MODEL_VERSION = "governance-model-v1";
    private static final String TARGET_VERSION = "governance-model-v2";
    private static final String TASK = "GOVERNANCE_TEST";

    @Autowired private JdbcClient jdbc;
    @Autowired private AdminService service;
    @Autowired private BusinessAuditService businessAudit;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.sql("""
                INSERT INTO role_definition(code, description) VALUES ('ADMIN', 'Administrator')
                ON DUPLICATE KEY UPDATE description = VALUES(description)
                """).update();
        jdbc.sql("""
                        INSERT INTO user_account(id, username, password_hash, display_name, enabled, last_active_role)
                        VALUES (:id, 'governance-admin', 'not-used', 'Governance Admin', TRUE, 'ADMIN')
                        """).param("id", ACTOR_ID.toString()).update();
        jdbc.sql("INSERT INTO user_role(user_id, role_code) VALUES (:id, 'ADMIN')")
                .param("id", ACTOR_ID.toString()).update();
        jdbc.sql("""
                        INSERT INTO processing_run(
                            id, pipeline_version, git_tree_sha256, config_sha256, random_seed,
                            status, started_at, completed_at, output_manifest_sha256)
                        VALUES (:id, 'test', REPEAT('1', 64), REPEAT('2', 64), 42,
                            'SUCCEEDED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), REPEAT('3', 64))
                        """).param("id", RUN_ID.toString()).update();
        jdbc.sql("""
                        INSERT INTO dataset_source(
                            id, source_key, name, official_url, license_name, license_url, citation_text)
                        VALUES (:id, 'GOVERNANCE_TEST', 'Governance test source',
                            'https://example.test/source', 'Test', 'https://example.test/license', 'Test citation')
                        """).param("id", SOURCE_ID.toString()).update();
        dataset(RETIRE_VERSION);
        dataset(MODEL_DATASET);
        model(MODEL_VERSION, "ACTIVE");
        model(TARGET_VERSION, "FROZEN");
        jdbc.sql("""
                        INSERT INTO model_deployment(
                            task_name, active_version_id, rollback_version_id, deployed_at, deployed_by)
                        VALUES (:task, :active, :rollback, CURRENT_TIMESTAMP(6), 'fixture')
                        """).param("task", TASK).param("active", MODEL_VERSION)
                .param("rollback", TARGET_VERSION).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM admin_audit_event WHERE actor_user_id = :actor")
                .param("actor", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM user_notification WHERE recipient_user_id = :actor")
                .param("actor", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM model_deployment_event WHERE task_name = :task")
                .param("task", TASK).update();
        jdbc.sql("DELETE FROM model_deployment WHERE task_name = :task")
                .param("task", TASK).update();
        jdbc.sql("DELETE FROM model_version WHERE version_id IN (:active, :target)")
                .param("active", MODEL_VERSION).param("target", TARGET_VERSION).update();
        jdbc.sql("DELETE FROM dataset_version WHERE version_id IN (:retire, :model)")
                .param("retire", RETIRE_VERSION).param("model", MODEL_DATASET).update();
        jdbc.sql("DELETE FROM dataset_source WHERE id = :id").param("id", SOURCE_ID.toString()).update();
        jdbc.sql("DELETE FROM processing_run WHERE id = :id").param("id", RUN_ID.toString()).update();
        jdbc.sql("UPDATE user_account SET last_active_role = NULL WHERE id = :id")
                .param("id", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", ACTOR_ID.toString()).update();
        jdbc.sql("DELETE FROM user_account WHERE id = :id").param("id", ACTOR_ID.toString()).update();
    }

    @Test
    void lifecycleUsesImpactPreviewAndRetirementIsFinal() {
        AdminDtos.DatasetImpact impact = service.datasetImpact(RETIRE_VERSION);
        assertTrue(impact.canRetire());
        assertTrue(impact.blockers().isEmpty());

        assertEquals("DEPRECATED", service.changeDatasetStatus(
                actor(), RETIRE_VERSION, "DEPRECATED", "Stop new references").lifecycleStatus());
        assertEquals("RETIRED", service.changeDatasetStatus(
                actor(), RETIRE_VERSION, "RETIRED", "No remaining references").lifecycleStatus());
        assertThrows(DomainException.class, () -> service.changeDatasetStatus(
                actor(), RETIRE_VERSION, "ENABLED", "Reactivation should fail"));
    }

    @Test
    void rollbackIsTaskScopedOptimisticAndAudited() {
        AdminDtos.ModelRollbackResult result = service.rollbackDeployment(actor(), TASK,
                new AdminDtos.ModelRollbackRequest(MODEL_VERSION, TARGET_VERSION, "Validated rollback"));
        assertEquals(TARGET_VERSION, result.deployment().activeVersionId());
        assertEquals(MODEL_VERSION, result.deployment().rollbackVersionId());
        assertFalse(result.affectedModules().isEmpty());

        assertThrows(DomainException.class, () -> service.rollbackDeployment(actor(), TASK,
                new AdminDtos.ModelRollbackRequest(MODEL_VERSION, TARGET_VERSION, "Stale rollback")));
        AdminDtos.AuditList audits = service.auditEvents(
                0, 20, null, null, ACTOR_ID, "MODEL_DEPLOYMENT_ROLLED_BACK", null, null);
        assertEquals(2, audits.total());
        assertTrue(audits.items().stream().anyMatch(item -> item.outcome().equals("FAILED")));
        assertTrue(audits.items().stream().allMatch(item -> item.correlationId() != null));
        assertTrue(service.exportAuditEvents(null, null, ACTOR_ID, null, null, null)
                .contains("MODEL_DEPLOYMENT_ROLLED_BACK"));
    }

    @Test
    void updatingTheActiveAdministratorAuditsAfterTheAccountTransactionCommits() {
        String correlationId = "b1000000-0000-0000-0000-000000000010";
        AdminDtos.AdminUser updated;
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            updated = service.updateUser(actor(), ACTOR_ID,
                    new AdminDtos.UserUpdateRequest(
                            "Updated Governance Admin", null, java.util.List.of("ADMIN"), true));
        }

        assertEquals("Updated Governance Admin", updated.displayName());
        AdminDtos.AuditList audits = service.auditEvents(
                0, 20, null, null, ACTOR_ID, "USER_UPDATED", "USER", "SUCCEEDED");
        assertEquals(1, audits.total());
        assertEquals("Updated Governance Admin", audits.items().getFirst().actorDisplayName());
        assertEquals(UUID.fromString(correlationId), audits.items().getFirst().correlationId());
    }

    @Test
    void afterCommitAuditFailureDoesNotTurnCommittedBusinessWorkIntoAnError() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> businessAudit.record(
                actor(), "X".repeat(100), "USER", ACTOR_ID.toString(), "SUCCEEDED",
                null, null, null, null, null)));
    }

    private void dataset(String version) {
        jdbc.sql("""
                        INSERT INTO dataset_version(
                            version_id, source_id, source_sha256, schema_sha256,
                            processing_config_sha256, manifest_sha256, processing_run_id,
                            row_count, processed_at, manifest_json, lifecycle_status)
                        VALUES (:version, :source, REPEAT('4', 64), REPEAT('5', 64),
                            REPEAT('6', 64), REPEAT('7', 64), :run, 1,
                            CURRENT_TIMESTAMP(6), JSON_OBJECT(), 'ENABLED')
                        """).param("version", version).param("source", SOURCE_ID.toString())
                .param("run", RUN_ID.toString()).update();
    }

    private void model(String version, String status) {
        jdbc.sql("""
                        INSERT INTO model_version(
                            version_id, model_family, task_name, dataset_version_id, status,
                            random_seed, config_json, feature_contract_sha256,
                            manifest_sha256, selected, frozen_at)
                        VALUES (:version, 'RULE', :task, :dataset, :status, 42,
                            JSON_OBJECT(), REPEAT('8', 64), REPEAT('9', 64), :selected,
                            CURRENT_TIMESTAMP(6))
                        """).param("version", version).param("task", TASK)
                .param("dataset", MODEL_DATASET).param("status", status)
                .param("selected", status.equals("ACTIVE")).update();
    }

    private static EduTwinPrincipal actor() {
        return new EduTwinPrincipal(
                ACTOR_ID, "governance-admin", "Governance Admin", "",
                UserRole.ADMIN, Set.of(), true);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
