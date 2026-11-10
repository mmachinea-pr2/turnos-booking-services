ALTER TABLE reservation_process
    ADD COLUMN reservation_id BIGINT NULL,
    ADD COLUMN result_event_id VARCHAR(100) COLLATE utf8mb4_bin,
    ADD COLUMN result_event_type VARCHAR(100),
    ADD COLUMN result_occurred_at TIMESTAMP(6) NULL,
    ADD COLUMN result_request_id VARCHAR(100) COLLATE utf8mb4_bin,
    ADD COLUMN result_reason VARCHAR(2000),
    ADD COLUMN result_message VARCHAR(2000),
    ADD COLUMN phone_rejection_event_id VARCHAR(100) COLLATE utf8mb4_bin;
ALTER TABLE phone_submission_outbox
    DROP INDEX uk_phone_submission_request,
    ADD COLUMN response_round VARCHAR(100) COLLATE utf8mb4_bin NOT NULL DEFAULT 'INITIAL',
    ADD CONSTRAINT uk_phone_submission_round UNIQUE (request_event_id,response_round);
