ALTER TABLE analysis_job
    ADD COLUMN error_retryable BOOLEAN NOT NULL DEFAULT FALSE AFTER error_message;
