INSERT INTO role_definition(code, description)
VALUES ('COUNSELOR', 'Read-only counselor access to assigned student cohorts and classes')
ON DUPLICATE KEY UPDATE description = VALUES(description);

ALTER TABLE course
    CHANGE COLUMN presentation term_label VARCHAR(80) NULL,
    MODIFY COLUMN credits DECIMAL(4,1) NOT NULL;

ALTER TABLE teaching_assignment
    ADD COLUMN assignment_role VARCHAR(20) NOT NULL DEFAULT 'CO_TEACHER' AFTER teacher_id,
    ADD COLUMN owner_course_id CHAR(36)
        GENERATED ALWAYS AS (
            CASE WHEN assignment_role = 'OWNER' THEN course_id ELSE NULL END
        ) STORED,
    ADD CONSTRAINT ck_teaching_assignment_role
        CHECK (assignment_role IN ('OWNER', 'CO_TEACHER')),
    ADD CONSTRAINT uk_teaching_assignment_owner UNIQUE (owner_course_id);

UPDATE teaching_assignment assignment
JOIN (
    SELECT course_id, MIN(teacher_id) teacher_id
    FROM teaching_assignment
    GROUP BY course_id
) owner
  ON owner.course_id = assignment.course_id
 AND owner.teacher_id = assignment.teacher_id
SET assignment.assignment_role = 'OWNER';

CREATE TABLE counselor_profile (
    user_id CHAR(36) PRIMARY KEY,
    staff_number VARCHAR(50),
    college VARCHAR(160),
    department VARCHAR(160),
    CONSTRAINT uk_counselor_profile_staff_number UNIQUE (staff_number),
    CONSTRAINT fk_counselor_profile_user
        FOREIGN KEY (user_id) REFERENCES user_account (id)
);

CREATE TABLE counselor_scope (
    counselor_id CHAR(36) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_key VARCHAR(500) NOT NULL,
    college VARCHAR(160) NOT NULL,
    major VARCHAR(160) NOT NULL,
    cohort_year SMALLINT UNSIGNED NOT NULL,
    class_name VARCHAR(200),
    created_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (counselor_id, scope_type, scope_key),
    CONSTRAINT ck_counselor_scope_type CHECK (scope_type IN ('COHORT', 'CLASS')),
    CONSTRAINT ck_counselor_scope_class
        CHECK (
            (scope_type = 'COHORT' AND class_name IS NULL)
            OR (scope_type = 'CLASS' AND class_name IS NOT NULL)
        ),
    CONSTRAINT fk_counselor_scope_counselor
        FOREIGN KEY (counselor_id) REFERENCES counselor_profile (user_id),
    CONSTRAINT fk_counselor_scope_creator
        FOREIGN KEY (created_by) REFERENCES user_account (id),
    INDEX ix_counselor_scope_lookup (
        counselor_id, college, major, cohort_year, class_name
    )
);
