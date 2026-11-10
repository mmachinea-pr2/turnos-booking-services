ALTER TABLE reservation_process
    ADD COLUMN cancellation_status VARCHAR(16),
    ADD COLUMN cancellation_reason VARCHAR(500),
    ADD COLUMN cancellation_started_at TIMESTAMP(6) NULL,
    ADD COLUMN cancelled_at TIMESTAMP(6) NULL,
    ADD COLUMN cancellation_error VARCHAR(60);
UPDATE reservation_process SET cancellation_status = 'CANCELLED', cancelled_at = result_occurred_at
WHERE status = 'CANCELLED';
