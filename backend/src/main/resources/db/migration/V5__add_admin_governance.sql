INSERT INTO role_definition(code, description)
VALUES ('ADMIN', 'System administrator for identity, course, model, and data governance')
ON DUPLICATE KEY UPDATE description = VALUES(description);

ALTER TABLE user_account
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE AFTER enabled;

ALTER TABLE dataset_version
    ADD COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ENABLED' AFTER manifest_json,
    ADD CONSTRAINT ck_dataset_version_lifecycle_status
        CHECK (lifecycle_status IN ('ENABLED', 'DISABLED'));

CREATE TABLE admin_audit_event (
    id CHAR(36) PRIMARY KEY,
    actor_user_id CHAR(36) NOT NULL,
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    before_json JSON,
    after_json JSON,
    outcome VARCHAR(20) NOT NULL,
    correlation_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_admin_audit_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT fk_admin_audit_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id),
    INDEX ix_admin_audit_created (created_at),
    INDEX ix_admin_audit_target (target_type, target_id, created_at)
);
