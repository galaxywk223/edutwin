CREATE TABLE assistant_conversation (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    active_role VARCHAR(30) NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6),
    CONSTRAINT fk_assistant_conversation_user
        FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_assistant_conversation_role
        FOREIGN KEY (active_role) REFERENCES role_definition (code),
    INDEX ix_assistant_conversation_owner (user_id, active_role, deleted_at, updated_at)
);

CREATE TABLE assistant_message (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    message_role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    content LONGTEXT,
    client_message_id CHAR(36),
    retry_of_message_id CHAR(36),
    route_context_json JSON,
    sources_json JSON,
    deep_links_json JSON,
    error_code VARCHAR(80),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    CONSTRAINT ck_assistant_message_role
        CHECK (message_role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_assistant_message_status
        CHECK (status IN ('COMPLETED', 'PROCESSING', 'FAILED', 'DELETED')),
    CONSTRAINT uk_assistant_message_client
        UNIQUE (conversation_id, client_message_id),
    CONSTRAINT fk_assistant_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES assistant_conversation (id),
    CONSTRAINT fk_assistant_message_retry
        FOREIGN KEY (retry_of_message_id) REFERENCES assistant_message (id),
    INDEX ix_assistant_message_conversation (conversation_id, created_at)
);

CREATE TABLE assistant_message_event (
    id CHAR(36) PRIMARY KEY,
    message_id CHAR(36) NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    data_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_assistant_message_event_type CHECK (event_type IN (
        'message.started', 'tool.started', 'tool.completed',
        'message.delta', 'message.completed', 'message.failed')),
    CONSTRAINT uk_assistant_message_event_sequence UNIQUE (message_id, sequence_no),
    CONSTRAINT fk_assistant_message_event_message
        FOREIGN KEY (message_id) REFERENCES assistant_message (id),
    INDEX ix_assistant_message_event_recovery (message_id, sequence_no)
);

CREATE TABLE assistant_tool_call (
    id CHAR(36) PRIMARY KEY,
    message_id CHAR(36) NOT NULL,
    call_index SMALLINT UNSIGNED NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_sha256 CHAR(64),
    result_json LONGTEXT,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(80),
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT ck_assistant_tool_call_status
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT uk_assistant_tool_call_index UNIQUE (message_id, call_index),
    CONSTRAINT fk_assistant_tool_call_message
        FOREIGN KEY (message_id) REFERENCES assistant_message (id)
);

ALTER TABLE ai_invocation
    DROP FOREIGN KEY fk_ai_invocation_job;

ALTER TABLE ai_invocation
    MODIFY COLUMN analysis_job_id CHAR(36) NULL,
    ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT 'DIAGNOSIS' AFTER id,
    ADD COLUMN actor_user_id CHAR(36) AFTER analysis_job_id,
    ADD COLUMN active_role VARCHAR(30) AFTER actor_user_id,
    ADD COLUMN assistant_message_id CHAR(36) AFTER active_role,
    ADD CONSTRAINT ck_ai_invocation_purpose
        CHECK (purpose IN ('DIAGNOSIS', 'ASSISTANT')),
    ADD CONSTRAINT fk_ai_invocation_job
        FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id),
    ADD CONSTRAINT fk_ai_invocation_actor
        FOREIGN KEY (actor_user_id) REFERENCES user_account (id),
    ADD CONSTRAINT fk_ai_invocation_role
        FOREIGN KEY (active_role) REFERENCES role_definition (code),
    ADD CONSTRAINT fk_ai_invocation_assistant_message
        FOREIGN KEY (assistant_message_id) REFERENCES assistant_message (id),
    ADD INDEX ix_ai_invocation_assistant (assistant_message_id, created_at);

ALTER TABLE dataset_version
    DROP CHECK ck_dataset_version_lifecycle_status;

UPDATE dataset_version
SET lifecycle_status = 'DEPRECATED'
WHERE lifecycle_status = 'DISABLED';

ALTER TABLE dataset_version
    ADD CONSTRAINT ck_dataset_version_lifecycle_status
        CHECK (lifecycle_status IN ('ENABLED', 'DEPRECATED', 'RETIRED'));

CREATE TABLE model_deployment_event (
    id CHAR(36) PRIMARY KEY,
    task_name VARCHAR(50) NOT NULL,
    expected_active_version_id VARCHAR(128) NOT NULL,
    previous_active_version_id VARCHAR(128) NOT NULL,
    target_version_id VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_model_deployment_event_deployment
        FOREIGN KEY (task_name) REFERENCES model_deployment (task_name),
    CONSTRAINT fk_model_deployment_event_actor
        FOREIGN KEY (actor_user_id) REFERENCES user_account (id),
    INDEX ix_model_deployment_event_task (task_name, created_at)
);

ALTER TABLE admin_audit_event
    ADD COLUMN active_role VARCHAR(30) AFTER actor_user_id,
    ADD COLUMN reason VARCHAR(500) AFTER target_id,
    ADD COLUMN error_code VARCHAR(80) AFTER outcome,
    ADD COLUMN metadata_json JSON AFTER after_json,
    ADD CONSTRAINT fk_admin_audit_role
        FOREIGN KEY (active_role) REFERENCES role_definition (code),
    ADD INDEX ix_admin_audit_filter (actor_user_id, action, target_type, outcome, created_at);
