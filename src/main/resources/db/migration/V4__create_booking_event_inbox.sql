CREATE TABLE booking_event_inbox (
    event_id VARCHAR(100) COLLATE utf8mb4_bin PRIMARY KEY,
    remote_process_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    processing_status VARCHAR(16) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    INDEX ix_booking_inbox_pending (processing_status, received_at)
);
ALTER TABLE reservation_process
    ADD COLUMN phone_request_event_id VARCHAR(100) COLLATE utf8mb4_bin;
