ALTER TABLE learning_plan
    DROP CHECK ck_learning_plan_status,
    ADD COLUMN expired_at TIMESTAMP(6) NULL AFTER superseded_at,
    ADD COLUMN regeneration_reason VARCHAR(40) NULL AFTER expired_at,
    ADD CONSTRAINT ck_learning_plan_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'COMPLETED', 'EXPIRED'));

ALTER TABLE learning_plan_item
    DROP CHECK ck_plan_item_status,
    ADD COLUMN started_at TIMESTAMP(6) NULL AFTER due_at,
    ADD COLUMN completed_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN skipped_at TIMESTAMP(6) NULL AFTER completed_at,
    ADD COLUMN skip_reason VARCHAR(300) NULL AFTER skipped_at,
    ADD CONSTRAINT ck_plan_item_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED'));

CREATE TABLE risk_case (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    source_risk_prediction_id CHAR(36),
    trigger_type VARCHAR(30) NOT NULL,
    risk_band VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    assigned_to CHAR(36),
    created_by CHAR(36),
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    resolved_at TIMESTAMP(6),
    closed_at TIMESTAMP(6),
    active_dedupe_key VARCHAR(120)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('OPEN', 'IN_PROGRESS')
                 THEN CONCAT(course_id, ':', student_id) ELSE NULL END
        ) STORED,
    CONSTRAINT ck_risk_case_trigger
        CHECK (trigger_type IN ('AUTO_HIGH', 'MANUAL_MEDIUM')),
    CONSTRAINT ck_risk_case_band CHECK (risk_band IN ('MEDIUM', 'HIGH')),
    CONSTRAINT ck_risk_case_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_risk_case_priority CHECK (priority IN ('MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT uk_risk_case_active_dedupe UNIQUE (active_dedupe_key),
    CONSTRAINT fk_risk_case_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_risk_case_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_risk_case_prediction
        FOREIGN KEY (source_risk_prediction_id) REFERENCES risk_prediction (id),
    CONSTRAINT fk_risk_case_assignee FOREIGN KEY (assigned_to) REFERENCES user_account (id),
    CONSTRAINT fk_risk_case_creator FOREIGN KEY (created_by) REFERENCES user_account (id),
    INDEX ix_risk_case_queue (assigned_to, status, priority, updated_at),
    INDEX ix_risk_case_student (student_id, status, updated_at)
);

CREATE TABLE risk_case_assignment_history (
    id CHAR(36) PRIMARY KEY,
    risk_case_id CHAR(36) NOT NULL,
    from_assignee CHAR(36),
    to_assignee CHAR(36) NOT NULL,
    changed_by CHAR(36) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_risk_assignment_case FOREIGN KEY (risk_case_id) REFERENCES risk_case (id),
    CONSTRAINT fk_risk_assignment_from FOREIGN KEY (from_assignee) REFERENCES user_account (id),
    CONSTRAINT fk_risk_assignment_to FOREIGN KEY (to_assignee) REFERENCES user_account (id),
    CONSTRAINT fk_risk_assignment_actor FOREIGN KEY (changed_by) REFERENCES user_account (id),
    INDEX ix_risk_assignment_case (risk_case_id, created_at)
);

CREATE TABLE risk_case_note (
    id CHAR(36) PRIMARY KEY,
    risk_case_id CHAR(36) NOT NULL,
    author_id CHAR(36) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_risk_note_case FOREIGN KEY (risk_case_id) REFERENCES risk_case (id),
    CONSTRAINT fk_risk_note_author FOREIGN KEY (author_id) REFERENCES user_account (id),
    INDEX ix_risk_note_case (risk_case_id, created_at)
);

CREATE TABLE risk_action_item (
    id CHAR(36) PRIMARY KEY,
    risk_case_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    due_at TIMESTAMP(6),
    result_summary VARCHAR(1000),
    created_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    CONSTRAINT ck_risk_action_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT uk_risk_action_case_id UNIQUE (risk_case_id, id),
    CONSTRAINT fk_risk_action_case FOREIGN KEY (risk_case_id) REFERENCES risk_case (id),
    CONSTRAINT fk_risk_action_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_risk_action_creator FOREIGN KEY (created_by) REFERENCES user_account (id),
    INDEX ix_risk_action_student (student_id, status, due_at)
);

CREATE TABLE risk_action_feedback (
    id CHAR(36) PRIMARY KEY,
    action_item_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_risk_feedback_action FOREIGN KEY (action_item_id) REFERENCES risk_action_item (id),
    CONSTRAINT fk_risk_feedback_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    INDEX ix_risk_feedback_action (action_item_id, created_at)
);
