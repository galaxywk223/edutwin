CREATE INDEX ix_learning_activity_course_time_type_student
    ON learning_activity_event (course_id, occurred_at, event_type, student_id);
