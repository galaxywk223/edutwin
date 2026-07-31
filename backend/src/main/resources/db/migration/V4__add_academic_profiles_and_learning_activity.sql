ALTER TABLE teacher_profile
    ADD COLUMN staff_number VARCHAR(20),
    ADD COLUMN college VARCHAR(120),
    ADD COLUMN department VARCHAR(120),
    ADD COLUMN academic_title VARCHAR(80),
    ADD CONSTRAINT uk_teacher_profile_staff_number UNIQUE (staff_number);

ALTER TABLE student_profile
    ADD COLUMN student_number VARCHAR(20),
    ADD COLUMN college VARCHAR(120),
    ADD COLUMN major VARCHAR(120),
    ADD COLUMN cohort_year SMALLINT UNSIGNED,
    ADD COLUMN class_name VARCHAR(80),
    ADD CONSTRAINT uk_student_profile_student_number UNIQUE (student_number);

ALTER TABLE course
    ADD COLUMN college VARCHAR(120),
    ADD COLUMN department VARCHAR(120),
    ADD COLUMN credits DECIMAL(3,1),
    ADD CONSTRAINT ck_course_credits CHECK (credits IS NULL OR credits > 0);

CREATE TABLE learning_activity_event (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    resource_type VARCHAR(40),
    resource_id CHAR(36),
    answer_event_id CHAR(36),
    occurred_at TIMESTAMP(6) NOT NULL,
    duration_seconds INT UNSIGNED,
    progress_percent DECIMAL(5,2),
    metadata_json JSON NOT NULL,
    synthetic BOOLEAN NOT NULL,
    data_version VARCHAR(100) NOT NULL,
    CONSTRAINT ck_learning_activity_event_type CHECK (event_type IN (
        'COURSE_ACCESS', 'LESSON_VIEW', 'VIDEO_PROGRESS', 'RESOURCE_VIEW',
        'RESOURCE_DOWNLOAD', 'PRACTICE_START', 'PRACTICE_SUBMIT',
        'ASSESSMENT_OPEN', 'ASSESSMENT_SUBMIT', 'DISCUSSION_VIEW'
    )),
    CONSTRAINT ck_learning_activity_progress CHECK (
        progress_percent IS NULL OR progress_percent BETWEEN 0 AND 100
    ),
    CONSTRAINT fk_learning_activity_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_learning_activity_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    CONSTRAINT fk_learning_activity_answer FOREIGN KEY (answer_event_id) REFERENCES answer_event (id),
    INDEX ix_learning_activity_course_student_time (course_id, student_id, occurred_at),
    INDEX ix_learning_activity_student_time (student_id, occurred_at),
    INDEX ix_learning_activity_course_type_time (course_id, event_type, occurred_at)
);
