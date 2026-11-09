ALTER TABLE reservation_process
    ADD COLUMN patient_first_name VARCHAR(100),
    ADD COLUMN patient_last_name VARCHAR(100),
    ADD COLUMN confirmation_started_at TIMESTAMP(6) NULL;
