ALTER TABLE course
    ADD COLUMN description VARCHAR(2000) NOT NULL DEFAULT '',
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    ADD CONSTRAINT ck_course_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'));

CREATE TABLE lms_section (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(500) NOT NULL,
    position INT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_lms_section_position UNIQUE (course_id, position),
    CONSTRAINT ck_lms_section_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_lms_section_course FOREIGN KEY (course_id) REFERENCES course (id),
    INDEX ix_lms_section_course_status (course_id, status, position)
);

CREATE TABLE lms_lesson (
    id CHAR(36) PRIMARY KEY,
    section_id CHAR(36) NOT NULL,
    title VARCHAR(180) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    body TEXT NOT NULL,
    resource_url VARCHAR(500),
    position INT UNSIGNED NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_lms_lesson_position UNIQUE (section_id, position),
    CONSTRAINT ck_lms_lesson_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_lms_lesson_section FOREIGN KEY (section_id) REFERENCES lms_section (id),
    INDEX ix_lms_lesson_section_status (section_id, status, position)
);

CREATE TABLE lms_assessment (
    id CHAR(36) PRIMARY KEY,
    course_id CHAR(36) NOT NULL,
    title VARCHAR(180) NOT NULL,
    description VARCHAR(500) NOT NULL,
    assessment_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    due_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_lms_assessment_type CHECK (assessment_type IN ('ASSIGNMENT', 'QUIZ')),
    CONSTRAINT ck_lms_assessment_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT fk_lms_assessment_course FOREIGN KEY (course_id) REFERENCES course (id),
    INDEX ix_lms_assessment_course_status (course_id, status, due_at)
);

CREATE TABLE lms_assessment_question (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    source_question_id CHAR(36),
    prompt TEXT NOT NULL,
    options_json JSON NOT NULL,
    correct_choice_id VARCHAR(80) NOT NULL,
    points DECIMAL(8,2) NOT NULL DEFAULT 1,
    position INT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_lms_assessment_question_position UNIQUE (assessment_id, position),
    CONSTRAINT ck_lms_assessment_question_points CHECK (points > 0),
    CONSTRAINT fk_lms_assessment_question_assessment FOREIGN KEY (assessment_id) REFERENCES lms_assessment (id),
    CONSTRAINT fk_lms_assessment_question_source FOREIGN KEY (source_question_id) REFERENCES question (id),
    INDEX ix_lms_assessment_question_assessment (assessment_id, position)
);

CREATE TABLE lms_submission (
    id CHAR(36) PRIMARY KEY,
    assessment_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    idempotency_key_hash CHAR(64) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    score DECIMAL(8,2) NOT NULL,
    max_score DECIMAL(8,2) NOT NULL,
    submitted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_lms_submission_student_assessment UNIQUE (assessment_id, student_id),
    CONSTRAINT uk_lms_submission_idempotency UNIQUE (student_id, idempotency_key_hash),
    CONSTRAINT fk_lms_submission_assessment FOREIGN KEY (assessment_id) REFERENCES lms_assessment (id),
    CONSTRAINT fk_lms_submission_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id),
    INDEX ix_lms_submission_student (student_id, submitted_at)
);

CREATE TABLE lms_submission_answer (
    submission_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    selected_choice_id VARCHAR(80) NOT NULL,
    correct BOOLEAN NOT NULL,
    points_awarded DECIMAL(8,2) NOT NULL,
    PRIMARY KEY (submission_id, question_id),
    CONSTRAINT fk_lms_submission_answer_submission FOREIGN KEY (submission_id) REFERENCES lms_submission (id),
    CONSTRAINT fk_lms_submission_answer_question FOREIGN KEY (question_id) REFERENCES lms_assessment_question (id)
);

CREATE TABLE lms_lesson_progress (
    lesson_id CHAR(36) NOT NULL,
    student_id CHAR(36) NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (lesson_id, student_id),
    CONSTRAINT fk_lms_lesson_progress_lesson FOREIGN KEY (lesson_id) REFERENCES lms_lesson (id),
    CONSTRAINT fk_lms_lesson_progress_student FOREIGN KEY (student_id) REFERENCES student_profile (user_id)
);

INSERT INTO lms_section (id, course_id, title, description, position, status)
SELECT UUID(), c.id, '课程导学', '从可验证的学习行为开始，熟悉课程目标与学习路径。', 1, 'PUBLISHED'
FROM course c
WHERE NOT EXISTS (SELECT 1 FROM lms_section s WHERE s.course_id = c.id);

INSERT INTO lms_lesson (id, section_id, title, summary, body, position, status)
SELECT UUID(), s.id, '认识你的学习孪生', '理解掌握度、风险与学习计划如何协同工作。',
       '本课程通过学习行为构建可验证的学习孪生。完成练习后，系统会异步更新掌握度、风险、学习计划和教师统计。',
       1, 'PUBLISHED'
FROM lms_section s
WHERE s.position = 1
  AND NOT EXISTS (SELECT 1 FROM lms_lesson l WHERE l.section_id = s.id);

INSERT INTO lms_assessment (id, course_id, title, description, assessment_type, status)
SELECT UUID(), c.id, '第一课小测', '用两道客观题检查对学习孪生闭环的理解。', 'QUIZ', 'PUBLISHED'
FROM course c
WHERE NOT EXISTS (SELECT 1 FROM lms_assessment a WHERE a.course_id = c.id);

INSERT INTO lms_assessment_question (id, assessment_id, prompt, options_json, correct_choice_id, points, position)
SELECT UUID(), a.id, '哪一项是 EduTwin 答题闭环的持久化事实来源？',
       JSON_ARRAY(JSON_OBJECT('choiceId','mysql','label','MySQL 业务事实'), JSON_OBJECT('choiceId','browser','label','浏览器缓存'), JSON_OBJECT('choiceId','model','label','模型服务内存')),
       'mysql', 1, 1
FROM lms_assessment a
WHERE a.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM lms_assessment_question q WHERE q.assessment_id = a.id);

INSERT INTO lms_assessment_question (id, assessment_id, prompt, options_json, correct_choice_id, points, position)
SELECT UUID(), a.id, '学生提交答案后，哪一项会异步刷新？',
       JSON_ARRAY(JSON_OBJECT('choiceId','twin','label','孪生快照与学习计划'), JSON_OBJECT('choiceId','theme','label','页面主题色'), JSON_OBJECT('choiceId','password','label','登录密码')),
       'twin', 1, 2
FROM lms_assessment a
WHERE a.status = 'PUBLISHED'
  AND (SELECT COUNT(*) FROM lms_assessment_question q WHERE q.assessment_id = a.id) = 1;
