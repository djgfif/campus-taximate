CREATE TABLE file_delete_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    path VARCHAR(1024) NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_file_delete_job_due ON file_delete_job(next_attempt_at, id);
