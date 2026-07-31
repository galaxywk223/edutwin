CREATE TABLE user_account (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_user_account_username UNIQUE (username)
);

CREATE TABLE role_definition (
    code VARCHAR(30) PRIMARY KEY,
    description VARCHAR(200) NOT NULL
);

CREATE TABLE user_role (
    user_id CHAR(36) NOT NULL,
    role_code VARCHAR(30) NOT NULL,
    PRIMARY KEY (user_id, role_code),
    CONSTRAINT uk_user_role_single_role UNIQUE (user_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_code) REFERENCES role_definition (code)
);

CREATE TABLE teacher_profile (
    user_id CHAR(36) PRIMARY KEY,
    staff_key VARCHAR(80) NOT NULL,
    CONSTRAINT uk_teacher_profile_staff_key UNIQUE (staff_key),
    CONSTRAINT fk_teacher_profile_user FOREIGN KEY (user_id) REFERENCES user_account (id)
);

CREATE TABLE student_profile (
    user_id CHAR(36) PRIMARY KEY,
    synthetic BOOLEAN NOT NULL,
    synthetic_key VARCHAR(80),
    split_name VARCHAR(10) NOT NULL,
    CONSTRAINT uk_student_profile_synthetic_key UNIQUE (synthetic_key),
    CONSTRAINT ck_student_profile_split CHECK (split_name IN ('train', 'validation', 'test')),
    CONSTRAINT fk_student_profile_user FOREIGN KEY (user_id) REFERENCES user_account (id)
);

CREATE TABLE course (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    title VARCHAR(160) NOT NULL,
    presentation VARCHAR(80) NOT NULL,
    starts_on DATE NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_course_code UNIQUE (code)
);

CREATE TABLE teaching_assignment (
    course_id CHAR(36) NOT NULL,
    teacher_id CHAR(36) NOT NULL,
    assigned_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (course_id, teacher_id),
    CONSTRAINT fk_teaching_assignment_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_teaching_assignment_teacher FOREIGN KEY (teacher_id) REFERENCES teacher_profile (user_id)
);

CREATE TABLE course_enrollment (
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    enrolled_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (course_id, student_id),
    CONSTRAINT ck_course_enrollment_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'WITHDRAWN')),
    CONSTRAINT fk_course_enrollment_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_course_enrollment_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id)
);

CREATE TABLE knowledge_skill (
    id CHAR(36) PRIMARY KEY,
    source_skill_key VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    CONSTRAINT uk_knowledge_skill_source UNIQUE (data_version, source_skill_key)
);

CREATE TABLE question (
    id CHAR(36) PRIMARY KEY,
    source_problem_key VARCHAR(100) NOT NULL,
    prompt_text TEXT NOT NULL,
    answer_type VARCHAR(30) NOT NULL,
    options_json JSON,
    correct_answer VARCHAR(500) NOT NULL,
    difficulty DECIMAL(8,6) NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT ck_question_difficulty CHECK (difficulty BETWEEN 0 AND 1),
    CONSTRAINT uk_question_source UNIQUE (data_version, source_problem_key)
);

CREATE TABLE question_skill (
    question_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    ordinal SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (question_id, skill_id),
    CONSTRAINT uk_question_skill_ordinal UNIQUE (question_id, ordinal),
    CONSTRAINT fk_question_skill_question FOREIGN KEY (question_id) REFERENCES question (id),
    CONSTRAINT fk_question_skill_skill FOREIGN KEY (skill_id) REFERENCES knowledge_skill (id)
);

CREATE TABLE course_question (
    course_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    active BOOLEAN NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    PRIMARY KEY (course_id, question_id),
    CONSTRAINT uk_course_question_ordinal UNIQUE (course_id, ordinal),
    CONSTRAINT fk_course_question_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_course_question_question FOREIGN KEY (question_id) REFERENCES question (id)
);

CREATE TABLE answer_event (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    source_order_id BIGINT,
    submitted_answer VARCHAR(500) NOT NULL,
    correct BOOLEAN NOT NULL,
    response_time_ms INT UNSIGNED NOT NULL,
    attempt_number SMALLINT UNSIGNED NOT NULL,
    event_sequence BIGINT UNSIGNED NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    data_version VARCHAR(100) NOT NULL,
    CONSTRAINT ck_answer_event_attempt CHECK (attempt_number >= 1),
    CONSTRAINT uk_answer_event_source UNIQUE (data_version, source_order_id),
    CONSTRAINT uk_answer_event_sequence UNIQUE (course_id, student_id, event_sequence),
    CONSTRAINT fk_answer_event_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_answer_event_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_answer_event_question FOREIGN KEY (question_id) REFERENCES question (id),
    INDEX ix_answer_event_student_course_time (student_id, course_id, occurred_at),
    INDEX ix_answer_event_course_time (course_id, occurred_at)
);

CREATE TABLE answer_event_skill (
    answer_event_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    ordinal SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (answer_event_id, skill_id),
    CONSTRAINT uk_answer_event_skill_ordinal UNIQUE (answer_event_id, ordinal),
    CONSTRAINT fk_answer_event_skill_event FOREIGN KEY (answer_event_id) REFERENCES answer_event (id),
    CONSTRAINT fk_answer_event_skill_skill FOREIGN KEY (skill_id) REFERENCES knowledge_skill (id)
);

CREATE TABLE analysis_job (
    id CHAR(36) PRIMARY KEY,
    answer_event_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    target_snapshot_id CHAR(36) NOT NULL,
    correlation_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    degraded BOOLEAN NOT NULL DEFAULT FALSE,
    degraded_stages JSON,
    attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    lease_owner VARCHAR(100),
    lease_expires_at TIMESTAMP(6),
    next_attempt_at TIMESTAMP(6),
    data_versions JSON NOT NULL,
    requested_model_versions JSON NOT NULL,
    effective_model_versions JSON NOT NULL,
    stream_message_id VARCHAR(100),
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT ck_analysis_job_status CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_analysis_job_stage CHECK (stage IN ('QUEUED', 'MODEL_INFERENCE', 'SNAPSHOT_PERSISTENCE', 'PLAN_GENERATION', 'EVENT_PUBLICATION', 'COMPLETED', 'FAILED')),
    CONSTRAINT uk_analysis_job_answer UNIQUE (answer_event_id),
    CONSTRAINT uk_analysis_job_snapshot UNIQUE (target_snapshot_id),
    CONSTRAINT uk_analysis_job_target_pair UNIQUE (id, target_snapshot_id),
    CONSTRAINT fk_analysis_job_answer FOREIGN KEY (answer_event_id) REFERENCES answer_event (id),
    CONSTRAINT fk_analysis_job_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_analysis_job_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    INDEX ix_analysis_job_status_created (status, created_at)
);

CREATE TABLE idempotency_record (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    resource_path VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    analysis_job_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_idempotency_scope UNIQUE (user_id, http_method, resource_path, idempotency_key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_idempotency_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id)
);

CREATE TABLE outbox_event (
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6),
    stream_message_id VARCHAR(100),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT uk_outbox_aggregate_event UNIQUE (aggregate_type, aggregate_id, event_type),
    INDEX ix_outbox_publish (status, available_at, created_at)
);

CREATE TABLE analysis_job_event (
    id CHAR(36) PRIMARY KEY,
    analysis_job_id CHAR(36) NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_analysis_job_event_sequence UNIQUE (analysis_job_id, sequence_no),
    CONSTRAINT fk_analysis_job_event_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id),
    INDEX ix_analysis_job_event_resume (analysis_job_id, sequence_no)
);

CREATE TABLE consumed_message (
    consumer_group VARCHAR(100) NOT NULL,
    message_id VARCHAR(100) NOT NULL,
    analysis_job_id CHAR(36) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_group, message_id),
    CONSTRAINT fk_consumed_message_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id)
);

CREATE TABLE knowledge_prediction (
    id CHAR(36) PRIMARY KEY,
    analysis_job_id CHAR(36) NOT NULL,
    answer_event_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    mastery_probability DECIMAL(9,8) NOT NULL,
    next_correct_probability DECIMAL(9,8) NOT NULL,
    mastery_model_version VARCHAR(128) NOT NULL,
    next_model_version VARCHAR(128) NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    degraded BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_knowledge_prediction_mastery CHECK (mastery_probability BETWEEN 0 AND 1),
    CONSTRAINT ck_knowledge_prediction_next CHECK (next_correct_probability BETWEEN 0 AND 1),
    CONSTRAINT uk_knowledge_prediction_job_skill UNIQUE (analysis_job_id, skill_id),
    CONSTRAINT fk_knowledge_prediction_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id),
    CONSTRAINT fk_knowledge_prediction_event FOREIGN KEY (answer_event_id) REFERENCES answer_event (id),
    CONSTRAINT fk_knowledge_prediction_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_knowledge_prediction_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_knowledge_prediction_skill FOREIGN KEY (skill_id) REFERENCES knowledge_skill (id)
);

CREATE TABLE risk_prediction (
    id CHAR(36) PRIMARY KEY,
    analysis_job_id CHAR(36) NOT NULL,
    answer_event_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    calibrated_probability DECIMAL(9,8) NOT NULL,
    risk_band VARCHAR(20) NOT NULL,
    base_value DECIMAL(14,10) NOT NULL,
    feature_values JSON NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    calibrated BOOLEAN NOT NULL,
    degraded BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_risk_prediction_probability CHECK (calibrated_probability BETWEEN 0 AND 1),
    CONSTRAINT ck_risk_prediction_band CHECK (risk_band IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT uk_risk_prediction_job UNIQUE (analysis_job_id),
    CONSTRAINT fk_risk_prediction_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id),
    CONSTRAINT fk_risk_prediction_event FOREIGN KEY (answer_event_id) REFERENCES answer_event (id),
    CONSTRAINT fk_risk_prediction_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_risk_prediction_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id)
);

CREATE TABLE twin_snapshot (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    snapshot_version BIGINT UNSIGNED NOT NULL,
    answer_event_id CHAR(36) NOT NULL,
    analysis_job_id CHAR(36) NOT NULL,
    knowledge_mastery JSON NOT NULL,
    next_correct_probability DECIMAL(9,8) NOT NULL,
    risk_probability DECIMAL(9,8) NOT NULL,
    engagement_score DECIMAL(9,8) NOT NULL,
    persistence_score DECIMAL(9,8) NOT NULL,
    plan_completion_rate DECIMAL(9,8) NOT NULL,
    data_versions JSON NOT NULL,
    model_versions JSON NOT NULL,
    degraded BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_twin_snapshot_next CHECK (next_correct_probability BETWEEN 0 AND 1),
    CONSTRAINT ck_twin_snapshot_risk CHECK (risk_probability BETWEEN 0 AND 1),
    CONSTRAINT ck_twin_snapshot_engagement CHECK (engagement_score BETWEEN 0 AND 1),
    CONSTRAINT ck_twin_snapshot_persistence CHECK (persistence_score BETWEEN 0 AND 1),
    CONSTRAINT ck_twin_snapshot_plan CHECK (plan_completion_rate BETWEEN 0 AND 1),
    CONSTRAINT uk_twin_snapshot_version UNIQUE (course_id, student_id, snapshot_version),
    CONSTRAINT uk_twin_snapshot_job UNIQUE (analysis_job_id),
    CONSTRAINT uk_twin_snapshot_event UNIQUE (answer_event_id),
    CONSTRAINT fk_twin_snapshot_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_twin_snapshot_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_twin_snapshot_event FOREIGN KEY (answer_event_id) REFERENCES answer_event (id),
    CONSTRAINT fk_twin_snapshot_job_target FOREIGN KEY (analysis_job_id, id) REFERENCES analysis_job (id, target_snapshot_id),
    INDEX ix_twin_snapshot_history (student_id, course_id, snapshot_version DESC)
);

CREATE TABLE twin_snapshot_skill (
    snapshot_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    mastery_probability DECIMAL(9,8) NOT NULL,
    next_correct_probability DECIMAL(9,8) NOT NULL,
    mastery_model_version VARCHAR(128) NOT NULL,
    next_model_version VARCHAR(128) NOT NULL,
    PRIMARY KEY (snapshot_id, skill_id),
    CONSTRAINT ck_twin_snapshot_skill_mastery CHECK (mastery_probability BETWEEN 0 AND 1),
    CONSTRAINT ck_twin_snapshot_skill_next CHECK (next_correct_probability BETWEEN 0 AND 1),
    CONSTRAINT fk_twin_snapshot_skill_snapshot FOREIGN KEY (snapshot_id) REFERENCES twin_snapshot (id),
    CONSTRAINT fk_twin_snapshot_skill_skill FOREIGN KEY (skill_id) REFERENCES knowledge_skill (id)
);

CREATE TABLE twin_current_pointer (
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    snapshot_id CHAR(36) NOT NULL,
    last_applied_answer_sequence BIGINT UNSIGNED NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (course_id, student_id),
    CONSTRAINT uk_twin_current_snapshot UNIQUE (snapshot_id),
    CONSTRAINT fk_twin_current_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_twin_current_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_twin_current_snapshot FOREIGN KEY (snapshot_id) REFERENCES twin_snapshot (id)
);

CREATE TABLE course_risk_current (
    course_id CHAR(36) PRIMARY KEY,
    mean_risk DECIMAL(9,8) NOT NULL,
    low_count INT UNSIGNED NOT NULL,
    medium_count INT UNSIGNED NOT NULL,
    high_count INT UNSIGNED NOT NULL,
    student_count INT UNSIGNED NOT NULL,
    aggregation_version BIGINT UNSIGNED NOT NULL,
    source_job_id CHAR(36) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_course_risk_mean CHECK (mean_risk BETWEEN 0 AND 1),
    CONSTRAINT ck_course_risk_counts CHECK (student_count = low_count + medium_count + high_count),
    CONSTRAINT fk_course_risk_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_course_risk_job FOREIGN KEY (source_job_id) REFERENCES analysis_job (id)
);

CREATE TABLE learning_plan (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    plan_version BIGINT UNSIGNED NOT NULL,
    source_snapshot_id CHAR(36) NOT NULL,
    source_job_id CHAR(36) NOT NULL,
    rule_version VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    valid_until TIMESTAMP(6) NOT NULL,
    data_versions JSON NOT NULL,
    model_versions JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    superseded_at TIMESTAMP(6),
    CONSTRAINT ck_learning_plan_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'COMPLETED')),
    CONSTRAINT uk_learning_plan_version UNIQUE (course_id, student_id, plan_version),
    CONSTRAINT fk_learning_plan_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_learning_plan_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_learning_plan_snapshot FOREIGN KEY (source_snapshot_id) REFERENCES twin_snapshot (id),
    CONSTRAINT fk_learning_plan_job FOREIGN KEY (source_job_id) REFERENCES analysis_job (id),
    INDEX ix_learning_plan_current (student_id, course_id, status)
);

CREATE TABLE learning_plan_item (
    id CHAR(36) PRIMARY KEY,
    learning_plan_id CHAR(36) NOT NULL,
    ordinal SMALLINT UNSIGNED NOT NULL,
    question_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    task_type VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    rationale_code VARCHAR(80) NOT NULL,
    target_mastery DECIMAL(9,8) NOT NULL,
    target_count SMALLINT UNSIGNED NOT NULL,
    completed_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    due_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT ck_plan_item_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_plan_item_counts CHECK (completed_count <= target_count),
    CONSTRAINT ck_plan_item_target_mastery CHECK (target_mastery BETWEEN 0 AND 1),
    CONSTRAINT uk_plan_item_ordinal UNIQUE (learning_plan_id, ordinal),
    CONSTRAINT fk_plan_item_plan FOREIGN KEY (learning_plan_id) REFERENCES learning_plan (id),
    CONSTRAINT fk_plan_item_question FOREIGN KEY (question_id) REFERENCES question (id),
    CONSTRAINT fk_plan_item_skill FOREIGN KEY (skill_id) REFERENCES knowledge_skill (id)
);

CREATE TABLE learning_plan_current_pointer (
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    learning_plan_id CHAR(36) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (course_id, student_id),
    CONSTRAINT uk_learning_plan_current_plan UNIQUE (learning_plan_id),
    CONSTRAINT fk_learning_plan_current_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_learning_plan_current_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_learning_plan_current_plan FOREIGN KEY (learning_plan_id) REFERENCES learning_plan (id)
);

CREATE TABLE diagnosis (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    snapshot_id CHAR(36) NOT NULL,
    analysis_job_id CHAR(36) NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    requested_model VARCHAR(100) NOT NULL,
    effective_model VARCHAR(128) NOT NULL,
    structured_content JSON NOT NULL,
    degraded BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_diagnosis_job UNIQUE (analysis_job_id),
    CONSTRAINT fk_diagnosis_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_diagnosis_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_diagnosis_snapshot FOREIGN KEY (snapshot_id) REFERENCES twin_snapshot (id),
    CONSTRAINT fk_diagnosis_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id)
);

CREATE TABLE diagnosis_evidence (
    id CHAR(36) PRIMARY KEY,
    diagnosis_id CHAR(36) NOT NULL,
    rank_no TINYINT UNSIGNED NOT NULL,
    feature_name VARCHAR(100) NOT NULL,
    raw_value JSON NOT NULL,
    direction VARCHAR(30) NOT NULL,
    contribution DECIMAL(14,10) NOT NULL,
    base_value DECIMAL(14,10) NOT NULL,
    output_unit VARCHAR(30) NOT NULL,
    risk_model_version VARCHAR(128) NOT NULL,
    CONSTRAINT ck_diagnosis_evidence_rank CHECK (rank_no BETWEEN 1 AND 5),
    CONSTRAINT ck_diagnosis_evidence_direction CHECK (direction IN ('INCREASES_RISK', 'DECREASES_RISK')),
    CONSTRAINT uk_diagnosis_evidence_rank UNIQUE (diagnosis_id, rank_no),
    CONSTRAINT uk_diagnosis_evidence_feature UNIQUE (diagnosis_id, feature_name),
    CONSTRAINT fk_diagnosis_evidence_diagnosis FOREIGN KEY (diagnosis_id) REFERENCES diagnosis (id)
);

CREATE TABLE ai_invocation (
    id CHAR(36) PRIMARY KEY,
    analysis_job_id CHAR(36) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    response_sha256 CHAR(64),
    status VARCHAR(20) NOT NULL,
    latency_ms INT UNSIGNED,
    error_code VARCHAR(80),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_ai_invocation_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'INVALID_SCHEMA')),
    CONSTRAINT fk_ai_invocation_job FOREIGN KEY (analysis_job_id) REFERENCES analysis_job (id),
    INDEX ix_ai_invocation_job_created (analysis_job_id, created_at)
);

CREATE TABLE dataset_source (
    id CHAR(36) PRIMARY KEY,
    source_key VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    official_url VARCHAR(1000) NOT NULL,
    license_name VARCHAR(160) NOT NULL,
    license_url VARCHAR(1000) NOT NULL,
    citation_text TEXT NOT NULL,
    CONSTRAINT uk_dataset_source_key UNIQUE (source_key)
);

CREATE TABLE dataset_version (
    version_id VARCHAR(100) PRIMARY KEY,
    source_id CHAR(36) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    schema_sha256 CHAR(64) NOT NULL,
    processing_config_sha256 CHAR(64) NOT NULL,
    manifest_sha256 CHAR(64) NOT NULL,
    processing_run_id CHAR(36) NOT NULL,
    row_count BIGINT UNSIGNED NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    manifest_json JSON NOT NULL,
    CONSTRAINT fk_dataset_version_source FOREIGN KEY (source_id) REFERENCES dataset_source (id)
);

CREATE TABLE source_file (
    id CHAR(36) PRIMARY KEY,
    dataset_version_id VARCHAR(100) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    download_url VARCHAR(1000) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    CONSTRAINT uk_source_file_name UNIQUE (dataset_version_id, file_name),
    CONSTRAINT fk_source_file_version FOREIGN KEY (dataset_version_id) REFERENCES dataset_version (version_id)
);

CREATE TABLE processing_run (
    id CHAR(36) PRIMARY KEY,
    pipeline_version VARCHAR(100) NOT NULL,
    git_tree_sha256 CHAR(64) NOT NULL,
    config_sha256 CHAR(64) NOT NULL,
    random_seed INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    output_manifest_sha256 CHAR(64),
    CONSTRAINT ck_processing_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

ALTER TABLE dataset_version
    ADD CONSTRAINT fk_dataset_version_processing_run
    FOREIGN KEY (processing_run_id) REFERENCES processing_run (id);

CREATE TABLE synthetic_match (
    id CHAR(36) PRIMARY KEY,
    synthetic_student_id CHAR(36) NOT NULL,
    assistments_user_sha256 CHAR(64) NOT NULL,
    oulad_student_sha256 CHAR(64) NOT NULL,
    split_name VARCHAR(10) NOT NULL,
    performance_z DECIMAL(14,10) NOT NULL,
    activity_z DECIMAL(14,10) NOT NULL,
    persistence_z DECIMAL(14,10) NOT NULL,
    match_distance DECIMAL(14,10) NOT NULL,
    random_seed INT NOT NULL,
    processing_run_id CHAR(36) NOT NULL,
    CONSTRAINT ck_synthetic_match_split CHECK (split_name IN ('train', 'validation', 'test')),
    CONSTRAINT ck_synthetic_match_assistments_sha256
        CHECK (assistments_user_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_synthetic_match_oulad_sha256
        CHECK (oulad_student_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT uk_synthetic_match_student UNIQUE (synthetic_student_id),
    CONSTRAINT uk_synthetic_match_assistments UNIQUE (assistments_user_sha256),
    CONSTRAINT uk_synthetic_match_oulad UNIQUE (oulad_student_sha256),
    CONSTRAINT fk_synthetic_match_student FOREIGN KEY (synthetic_student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_synthetic_match_run FOREIGN KEY (processing_run_id) REFERENCES processing_run (id)
);

CREATE TABLE model_version (
    version_id VARCHAR(128) PRIMARY KEY,
    model_family VARCHAR(50) NOT NULL,
    task_name VARCHAR(50) NOT NULL,
    dataset_version_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    random_seed INT NOT NULL,
    config_json JSON NOT NULL,
    feature_contract_sha256 CHAR(64) NOT NULL,
    calibrator_type VARCHAR(50),
    calibrator_sha256 CHAR(64),
    manifest_sha256 CHAR(64) NOT NULL,
    selected BOOLEAN NOT NULL,
    frozen_at TIMESTAMP(6),
    test_evaluated_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_model_version_status CHECK (status IN ('CANDIDATE', 'FROZEN', 'ACTIVE', 'ROLLED_BACK')),
    CONSTRAINT fk_model_version_dataset FOREIGN KEY (dataset_version_id) REFERENCES dataset_version (version_id)
);

CREATE TABLE model_metric (
    model_version_id VARCHAR(128) NOT NULL,
    split_name VARCHAR(20) NOT NULL,
    metric_name VARCHAR(50) NOT NULL,
    metric_value DECIMAL(18,12) NOT NULL,
    measured_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (model_version_id, split_name, metric_name),
    CONSTRAINT ck_model_metric_split CHECK (split_name IN ('train', 'validation', 'test', 'latency')),
    CONSTRAINT fk_model_metric_version FOREIGN KEY (model_version_id) REFERENCES model_version (version_id)
);

CREATE TABLE model_artifact (
    id CHAR(36) PRIMARY KEY,
    model_version_id VARCHAR(128) NOT NULL,
    artifact_role VARCHAR(50) NOT NULL,
    artifact_uri VARCHAR(1000) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    dependency_versions JSON NOT NULL,
    CONSTRAINT uk_model_artifact_role UNIQUE (model_version_id, artifact_role),
    CONSTRAINT fk_model_artifact_version FOREIGN KEY (model_version_id) REFERENCES model_version (version_id)
);

CREATE TABLE model_deployment (
    task_name VARCHAR(50) PRIMARY KEY,
    active_version_id VARCHAR(128) NOT NULL,
    rollback_version_id VARCHAR(128),
    deployed_at TIMESTAMP(6) NOT NULL,
    deployed_by VARCHAR(100) NOT NULL,
    CONSTRAINT fk_model_deployment_active FOREIGN KEY (active_version_id) REFERENCES model_version (version_id),
    CONSTRAINT fk_model_deployment_rollback FOREIGN KEY (rollback_version_id) REFERENCES model_version (version_id)
);

CREATE TRIGGER trg_twin_snapshot_no_update
BEFORE UPDATE ON twin_snapshot
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'twin_snapshot is immutable';

CREATE TRIGGER trg_twin_snapshot_no_delete
BEFORE DELETE ON twin_snapshot
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'twin_snapshot is immutable';

CREATE TRIGGER trg_twin_snapshot_skill_no_update
BEFORE UPDATE ON twin_snapshot_skill
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'twin_snapshot_skill is immutable';

CREATE TRIGGER trg_twin_snapshot_skill_no_delete
BEFORE DELETE ON twin_snapshot_skill
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'twin_snapshot_skill is immutable';
