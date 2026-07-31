ALTER TABLE user_role DROP INDEX uk_user_role_single_role;

ALTER TABLE user_account
    ADD COLUMN token_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN last_active_role VARCHAR(30),
    ADD CONSTRAINT fk_user_account_last_active_role
        FOREIGN KEY (last_active_role) REFERENCES role_definition (code);

ALTER TABLE user_role
    ADD COLUMN granted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN granted_by CHAR(36),
    ADD CONSTRAINT fk_user_role_granted_by FOREIGN KEY (granted_by) REFERENCES user_account (id) ON DELETE SET NULL;

UPDATE user_account u
JOIN user_role ur ON ur.user_id = u.id
SET u.last_active_role = ur.role_code
WHERE u.last_active_role IS NULL;

CREATE TABLE user_role_history (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    role_code VARCHAR(30) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    changed_by CHAR(36),
    reason VARCHAR(500),
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_user_role_history_event CHECK (event_type IN ('GRANTED', 'REVOKED')),
    CONSTRAINT fk_user_role_history_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_history_role FOREIGN KEY (role_code) REFERENCES role_definition (code),
    CONSTRAINT fk_user_role_history_actor FOREIGN KEY (changed_by) REFERENCES user_account (id) ON DELETE SET NULL,
    INDEX ix_user_role_history_user_time (user_id, occurred_at)
);

INSERT INTO user_role_history(id, user_id, role_code, event_type, occurred_at)
SELECT UUID(), user_id, role_code, 'GRANTED', granted_at FROM user_role;

CREATE TABLE organization_unit (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    unit_type VARCHAR(20) NOT NULL,
    parent_id CHAR(36),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_organization_unit_code UNIQUE (code),
    CONSTRAINT ck_organization_unit_type CHECK (unit_type IN ('COLLEGE', 'DEPARTMENT', 'MAJOR', 'CLASS')),
    CONSTRAINT fk_organization_unit_parent FOREIGN KEY (parent_id) REFERENCES organization_unit (id),
    INDEX ix_organization_unit_parent (parent_id, unit_type, display_name)
);

CREATE TABLE user_organization_membership (
    user_id CHAR(36) NOT NULL,
    organization_id CHAR(36) NOT NULL,
    membership_type VARCHAR(20) NOT NULL DEFAULT 'PRIMARY',
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at TIMESTAMP(6),
    PRIMARY KEY (user_id, organization_id, membership_type),
    CONSTRAINT ck_user_org_membership_type CHECK (membership_type IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT fk_user_org_membership_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_org_membership_org FOREIGN KEY (organization_id) REFERENCES organization_unit (id),
    INDEX ix_user_org_membership_org (organization_id, ended_at)
);

CREATE TABLE academic_term (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_academic_term_code UNIQUE (code),
    CONSTRAINT ck_academic_term_dates CHECK (ends_on >= starts_on)
);

CREATE TABLE user_profile (
    user_id CHAR(36) PRIMARY KEY,
    email VARCHAR(254),
    phone VARCHAR(30),
    avatar_key VARCHAR(40) NOT NULL DEFAULT 'avatar-1',
    theme_color VARCHAR(20) NOT NULL DEFAULT 'teal',
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_user_profile_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT uk_user_profile_email UNIQUE (email),
    CONSTRAINT uk_user_profile_phone UNIQUE (phone)
);

INSERT INTO user_profile(user_id) SELECT id FROM user_account;

CREATE TABLE user_notification (
    id CHAR(36) PRIMARY KEY,
    recipient_user_id CHAR(36) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    title VARCHAR(160) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    deep_link VARCHAR(500),
    dedupe_key VARCHAR(200) NOT NULL,
    read_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6),
    CONSTRAINT uk_user_notification_dedupe UNIQUE (recipient_user_id, dedupe_key),
    CONSTRAINT fk_user_notification_recipient FOREIGN KEY (recipient_user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    INDEX ix_user_notification_recipient_time (recipient_user_id, created_at),
    INDEX ix_user_notification_unread (recipient_user_id, read_at, created_at)
);

INSERT IGNORE INTO organization_unit(id, code, display_name, unit_type)
SELECT UUID(), CONCAT('LEGACY-COLLEGE-', LEFT(SHA2(LOWER(TRIM(college)), 256), 16)), TRIM(college), 'COLLEGE'
FROM (
    SELECT college FROM teacher_profile WHERE college IS NOT NULL AND TRIM(college) <> ''
    UNION SELECT college FROM student_profile WHERE college IS NOT NULL AND TRIM(college) <> ''
) legacy_colleges;

INSERT IGNORE INTO organization_unit(id, code, display_name, unit_type, parent_id)
SELECT UUID(), CONCAT('LEGACY-DEPARTMENT-', LEFT(SHA2(CONCAT(LOWER(TRIM(tp.college)), '|', LOWER(TRIM(tp.department))), 256), 16)),
       TRIM(tp.department), 'DEPARTMENT', college.id
FROM teacher_profile tp
LEFT JOIN organization_unit college ON college.code = CONCAT('LEGACY-COLLEGE-', LEFT(SHA2(LOWER(TRIM(tp.college)), 256), 16))
WHERE tp.department IS NOT NULL AND TRIM(tp.department) <> '';

INSERT IGNORE INTO organization_unit(id, code, display_name, unit_type, parent_id)
SELECT UUID(), CONCAT('LEGACY-MAJOR-', LEFT(SHA2(CONCAT(LOWER(TRIM(sp.college)), '|', LOWER(TRIM(sp.major))), 256), 16)),
       TRIM(sp.major), 'MAJOR', college.id
FROM student_profile sp
LEFT JOIN organization_unit college ON college.code = CONCAT('LEGACY-COLLEGE-', LEFT(SHA2(LOWER(TRIM(sp.college)), 256), 16))
WHERE sp.major IS NOT NULL AND TRIM(sp.major) <> '';

INSERT IGNORE INTO organization_unit(id, code, display_name, unit_type, parent_id)
SELECT UUID(), CONCAT('LEGACY-CLASS-', LEFT(SHA2(CONCAT(LOWER(TRIM(sp.college)), '|', LOWER(TRIM(sp.major)), '|', LOWER(TRIM(sp.class_name))), 256), 16)),
       TRIM(sp.class_name), 'CLASS', major.id
FROM student_profile sp
LEFT JOIN organization_unit major ON major.code = CONCAT('LEGACY-MAJOR-', LEFT(SHA2(CONCAT(LOWER(TRIM(sp.college)), '|', LOWER(TRIM(sp.major))), 256), 16))
WHERE sp.class_name IS NOT NULL AND TRIM(sp.class_name) <> '';

INSERT IGNORE INTO user_organization_membership(user_id, organization_id, membership_type)
SELECT sp.user_id, COALESCE(class_unit.id, major.id, college.id), 'PRIMARY'
FROM student_profile sp
LEFT JOIN organization_unit college ON college.code = CONCAT('LEGACY-COLLEGE-', LEFT(SHA2(LOWER(TRIM(sp.college)), 256), 16))
LEFT JOIN organization_unit major ON major.code = CONCAT('LEGACY-MAJOR-', LEFT(SHA2(CONCAT(LOWER(TRIM(sp.college)), '|', LOWER(TRIM(sp.major))), 256), 16))
LEFT JOIN organization_unit class_unit ON class_unit.code = CONCAT('LEGACY-CLASS-', LEFT(SHA2(CONCAT(LOWER(TRIM(sp.college)), '|', LOWER(TRIM(sp.major)), '|', LOWER(TRIM(sp.class_name))), 256), 16))
WHERE COALESCE(class_unit.id, major.id, college.id) IS NOT NULL;

INSERT IGNORE INTO user_organization_membership(user_id, organization_id, membership_type)
SELECT tp.user_id, COALESCE(department.id, college.id), 'PRIMARY'
FROM teacher_profile tp
LEFT JOIN organization_unit college ON college.code = CONCAT('LEGACY-COLLEGE-', LEFT(SHA2(LOWER(TRIM(tp.college)), 256), 16))
LEFT JOIN organization_unit department ON department.code = CONCAT('LEGACY-DEPARTMENT-', LEFT(SHA2(CONCAT(LOWER(TRIM(tp.college)), '|', LOWER(TRIM(tp.department))), 256), 16))
WHERE COALESCE(department.id, college.id) IS NOT NULL;

INSERT IGNORE INTO academic_term(id, code, display_name, starts_on, ends_on)
SELECT UUID(), CONCAT('LEGACY-TERM-', LEFT(SHA2(LOWER(MIN(TRIM(term_label))), 256), 16)),
       MIN(TRIM(term_label)), MIN(starts_on), DATE_ADD(MAX(starts_on), INTERVAL 180 DAY)
FROM course WHERE term_label IS NOT NULL AND TRIM(term_label) <> ''
GROUP BY TRIM(term_label);

ALTER TABLE course
    ADD COLUMN organization_id CHAR(36),
    ADD COLUMN academic_term_id CHAR(36),
    ADD CONSTRAINT fk_course_organization FOREIGN KEY (organization_id) REFERENCES organization_unit (id),
    ADD CONSTRAINT fk_course_academic_term FOREIGN KEY (academic_term_id) REFERENCES academic_term (id);

UPDATE course c
LEFT JOIN organization_unit department ON department.code = CONCAT(
    'LEGACY-DEPARTMENT-', LEFT(SHA2(CONCAT(LOWER(TRIM(c.college)), '|', LOWER(TRIM(c.department))), 256), 16))
LEFT JOIN organization_unit college ON college.code = CONCAT(
    'LEGACY-COLLEGE-', LEFT(SHA2(LOWER(TRIM(c.college)), 256), 16))
LEFT JOIN academic_term term ON term.code = CONCAT(
    'LEGACY-TERM-', LEFT(SHA2(LOWER(TRIM(c.term_label)), 256), 16))
SET c.organization_id = COALESCE(department.id, college.id), c.academic_term_id = term.id;
