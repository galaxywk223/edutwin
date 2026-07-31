ALTER TABLE course
    DROP CHECK ck_course_status,
    ADD CONSTRAINT ck_course_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'));

ALTER TABLE lms_assessment
    DROP CHECK ck_lms_assessment_status,
    ADD COLUMN published_at TIMESTAMP(6) NULL AFTER due_at,
    ADD COLUMN closed_at TIMESTAMP(6) NULL AFTER published_at,
    ADD COLUMN cancelled_at TIMESTAMP(6) NULL AFTER closed_at,
    ADD COLUMN cancellation_reason VARCHAR(500) NULL AFTER cancelled_at,
    ADD CONSTRAINT ck_lms_assessment_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'CANCELLED', 'ARCHIVED'));

UPDATE lms_assessment
SET published_at = COALESCE(updated_at, created_at)
WHERE status = 'PUBLISHED' AND published_at IS NULL;

CREATE TABLE lms_course_status_history (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    previous_status VARCHAR(20) NULL,
    target_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id CHAR(36) NULL,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_lms_course_status_history_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_lms_course_status_history_actor FOREIGN KEY (actor_id) REFERENCES user_account (id),
    INDEX ix_lms_course_status_history_course (course_id, changed_at)
);

INSERT INTO lms_course_status_history(id, course_id, previous_status, target_status, reason, actor_id, changed_at)
SELECT UUID(), id, NULL, status, 'MIGRATED_EXISTING_STATE', NULL, COALESCE(updated_at, created_at)
FROM course;

CREATE TABLE lms_content_status_history (
    id CHAR(36) PRIMARY KEY,
    entity_type VARCHAR(20) NOT NULL,
    entity_id CHAR(36) NOT NULL,
    course_id CHAR(36) NOT NULL,
    previous_status VARCHAR(20) NULL,
    target_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id CHAR(36) NULL,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_lms_content_status_history_type CHECK (entity_type IN ('SECTION', 'LESSON')),
    CONSTRAINT fk_lms_content_status_history_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_lms_content_status_history_actor FOREIGN KEY (actor_id) REFERENCES user_account (id),
    INDEX ix_lms_content_status_history_entity (entity_type, entity_id, changed_at),
    INDEX ix_lms_content_status_history_course (course_id, changed_at)
);

INSERT INTO lms_content_status_history(
    id, entity_type, entity_id, course_id, previous_status, target_status, reason, actor_id, changed_at)
SELECT UUID(), 'SECTION', s.id, s.course_id, NULL, s.status, 'MIGRATED_EXISTING_STATE', NULL, s.created_at
FROM lms_section s;

INSERT INTO lms_content_status_history(
    id, entity_type, entity_id, course_id, previous_status, target_status, reason, actor_id, changed_at)
SELECT UUID(), 'LESSON', l.id, s.course_id, NULL, l.status, 'MIGRATED_EXISTING_STATE', NULL, l.created_at
FROM lms_lesson l
JOIN lms_section s ON s.id = l.section_id;

CREATE TABLE lms_assessment_status_history (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    previous_status VARCHAR(20) NULL,
    target_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id CHAR(36) NULL,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_lms_assessment_status_history_assessment
        FOREIGN KEY (assessment_id) REFERENCES lms_assessment (id),
    CONSTRAINT fk_lms_assessment_status_history_actor FOREIGN KEY (actor_id) REFERENCES user_account (id),
    INDEX ix_lms_assessment_status_history_assessment (assessment_id, changed_at)
);

INSERT INTO lms_assessment_status_history(
    id, assessment_id, previous_status, target_status, reason, actor_id, changed_at)
SELECT UUID(), id, NULL, status, 'MIGRATED_EXISTING_STATE', NULL, COALESCE(updated_at, created_at)
FROM lms_assessment;

CREATE TABLE lms_enrollment_history (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    previous_status VARCHAR(20) NULL,
    target_status VARCHAR(20) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id CHAR(36) NULL,
    effective_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_lms_enrollment_history_action
        CHECK (action_type IN ('JOINED', 'WITHDRAWN', 'RESTORED', 'COMPLETED')),
    CONSTRAINT fk_lms_enrollment_history_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_lms_enrollment_history_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_lms_enrollment_history_actor FOREIGN KEY (actor_id) REFERENCES user_account (id),
    INDEX ix_lms_enrollment_history_course_student (course_id, student_id, effective_at),
    INDEX ix_lms_enrollment_history_actor (actor_id, effective_at)
);

INSERT INTO lms_enrollment_history(
    id, course_id, student_id, previous_status, target_status, action_type, reason, actor_id, effective_at)
SELECT UUID(), course_id, student_id, NULL, status,
       CASE status WHEN 'ACTIVE' THEN 'JOINED' WHEN 'COMPLETED' THEN 'COMPLETED' ELSE 'WITHDRAWN' END,
       'MIGRATED_EXISTING_STATE', NULL, enrolled_at
FROM course_enrollment;

CREATE TABLE lms_attempt_request (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    request_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(500) NOT NULL,
    requested_due_at TIMESTAMP(6) NULL,
    personal_due_at TIMESTAMP(6) NULL,
    requested_by CHAR(36) NOT NULL,
    decided_by CHAR(36) NULL,
    decision_reason VARCHAR(500) NULL,
    requested_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    decided_at TIMESTAMP(6) NULL,
    consumed_at TIMESTAMP(6) NULL,
    CONSTRAINT ck_lms_attempt_request_type CHECK (request_type IN ('MAKEUP', 'RETAKE', 'APPEAL')),
    CONSTRAINT ck_lms_attempt_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT fk_lms_attempt_request_assessment FOREIGN KEY (assessment_id) REFERENCES lms_assessment (id),
    CONSTRAINT fk_lms_attempt_request_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_lms_attempt_request_requested_by FOREIGN KEY (requested_by) REFERENCES user_account (id),
    CONSTRAINT fk_lms_attempt_request_decided_by FOREIGN KEY (decided_by) REFERENCES user_account (id),
    INDEX ix_lms_attempt_request_student (student_id, status, requested_at),
    INDEX ix_lms_attempt_request_assessment (assessment_id, status, requested_at)
);

ALTER TABLE lms_submission
    DROP INDEX uk_lms_submission_student_assessment,
    ADD COLUMN attempt_number INT UNSIGNED NOT NULL DEFAULT 1 AFTER student_id,
    ADD COLUMN attempt_type VARCHAR(20) NOT NULL DEFAULT 'INITIAL' AFTER attempt_number,
    ADD COLUMN grant_request_id CHAR(36) NULL AFTER attempt_type,
    ADD COLUMN valid_for_grade BOOLEAN NOT NULL DEFAULT TRUE AFTER max_score,
    ADD CONSTRAINT ck_lms_submission_attempt_number CHECK (attempt_number > 0),
    ADD CONSTRAINT ck_lms_submission_attempt_type CHECK (attempt_type IN ('INITIAL', 'MAKEUP', 'RETAKE', 'APPEAL')),
    ADD CONSTRAINT uk_lms_submission_attempt UNIQUE (assessment_id, student_id, attempt_number),
    ADD CONSTRAINT uk_lms_submission_grant UNIQUE (grant_request_id),
    ADD CONSTRAINT fk_lms_submission_grant FOREIGN KEY (grant_request_id) REFERENCES lms_attempt_request (id),
    ADD INDEX ix_lms_submission_current_grade (assessment_id, student_id, valid_for_grade, score);
