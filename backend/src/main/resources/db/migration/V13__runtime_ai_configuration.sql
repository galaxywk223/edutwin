CREATE TABLE ai_runtime_configuration_version (
    id CHAR(36) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    api_base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    api_key_ciphertext VARBINARY(1024),
    api_key_iv BINARY(12),
    api_key_fingerprint CHAR(64),
    last_test_status VARCHAR(20) NOT NULL,
    last_tested_at TIMESTAMP(6),
    last_test_latency_ms INT UNSIGNED,
    created_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_ai_runtime_key_material CHECK (
        (api_key_ciphertext IS NULL AND api_key_iv IS NULL AND api_key_fingerprint IS NULL)
        OR (api_key_ciphertext IS NOT NULL AND api_key_iv IS NOT NULL AND api_key_fingerprint IS NOT NULL)
    ),
    CONSTRAINT ck_ai_runtime_test_status CHECK (last_test_status IN ('PASSED', 'SKIPPED_DISABLED')),
    CONSTRAINT fk_ai_runtime_version_actor FOREIGN KEY (created_by) REFERENCES user_account (id),
    INDEX ix_ai_runtime_version_created (created_at)
);

CREATE TABLE ai_runtime_configuration_state (
    id TINYINT UNSIGNED PRIMARY KEY,
    active_version_id CHAR(36),
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_by CHAR(36),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_ai_runtime_state_singleton CHECK (id = 1),
    CONSTRAINT fk_ai_runtime_state_version
        FOREIGN KEY (active_version_id) REFERENCES ai_runtime_configuration_version (id),
    CONSTRAINT fk_ai_runtime_state_actor FOREIGN KEY (updated_by) REFERENCES user_account (id)
);

INSERT INTO ai_runtime_configuration_state(id, active_version_id, revision, updated_by)
VALUES (1, NULL, 0, NULL);

ALTER TABLE analysis_job
    ADD COLUMN ai_configuration_version_id CHAR(36) NULL AFTER effective_model_versions,
    ADD CONSTRAINT fk_analysis_job_ai_configuration
        FOREIGN KEY (ai_configuration_version_id) REFERENCES ai_runtime_configuration_version (id);

ALTER TABLE ai_invocation
    ADD COLUMN ai_configuration_version_id CHAR(36) NULL AFTER assistant_message_id,
    ADD CONSTRAINT fk_ai_invocation_configuration
        FOREIGN KEY (ai_configuration_version_id) REFERENCES ai_runtime_configuration_version (id),
    ADD INDEX ix_ai_invocation_configuration (ai_configuration_version_id, created_at);
