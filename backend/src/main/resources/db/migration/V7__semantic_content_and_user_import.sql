ALTER TABLE user_account
    ADD COLUMN origin_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL' AFTER must_change_password,
    ADD CONSTRAINT ck_user_account_origin_type
        CHECK (origin_type IN ('DEMO_SYNTHETIC', 'MANUAL', 'BULK_IMPORT'));

UPDATE user_account u
LEFT JOIN student_profile sp ON sp.user_id = u.id
LEFT JOIN teacher_profile tp ON tp.user_id = u.id
SET u.origin_type = 'DEMO_SYNTHETIC'
WHERE sp.synthetic = TRUE OR tp.staff_key LIKE 'DEMO-TEACHER-%';

ALTER TABLE knowledge_skill
    ADD COLUMN content_origin VARCHAR(30) NOT NULL DEFAULT 'SOURCE_COMPATIBLE',
    ADD CONSTRAINT ck_knowledge_skill_content_origin
        CHECK (content_origin IN ('CURATED_SYNTHETIC', 'TEACHER_AUTHORED', 'SOURCE_COMPATIBLE'));

ALTER TABLE question
    ADD COLUMN knowledge_model_mode VARCHAR(30) NOT NULL DEFAULT 'ASSISTMENTS_FROZEN',
    ADD COLUMN content_origin VARCHAR(30) NOT NULL DEFAULT 'SOURCE_COMPATIBLE',
    ADD CONSTRAINT ck_question_knowledge_model_mode
        CHECK (knowledge_model_mode IN ('ONLINE_BKT', 'ASSISTMENTS_FROZEN')),
    ADD CONSTRAINT ck_question_content_origin
        CHECK (content_origin IN ('CURATED_SYNTHETIC', 'TEACHER_AUTHORED', 'SOURCE_COMPATIBLE'));

CREATE TABLE lms_assessment_question_skill (
    question_id CHAR(36) NOT NULL,
    skill_id CHAR(36) NOT NULL,
    ordinal SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (question_id, skill_id),
    CONSTRAINT uk_lms_assessment_question_skill_ordinal UNIQUE (question_id, ordinal),
    CONSTRAINT fk_lms_assessment_question_skill_question
        FOREIGN KEY (question_id) REFERENCES lms_assessment_question (id),
    CONSTRAINT fk_lms_assessment_question_skill_skill
        FOREIGN KEY (skill_id) REFERENCES knowledge_skill (id)
);

INSERT INTO lms_assessment_question_skill(question_id, skill_id, ordinal)
SELECT aq.id, qs.skill_id, qs.ordinal
FROM lms_assessment_question aq
JOIN question_skill qs ON qs.question_id = aq.source_question_id;
