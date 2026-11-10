CREATE TABLE phone_submission_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    process_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_event_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    topic VARCHAR(249) NOT NULL,
    message_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    payload JSON NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    delivery_status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_phone_submission_process FOREIGN KEY (process_id) REFERENCES reservation_process(id),
    CONSTRAINT uk_phone_submission_request UNIQUE (request_event_id),
    INDEX ix_phone_submission_due (delivery_status, next_attempt_at, lease_until)
);
