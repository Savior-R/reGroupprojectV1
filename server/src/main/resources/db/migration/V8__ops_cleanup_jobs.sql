-- Operational cleanup jobs.
CREATE TABLE ops.cleanup_jobs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    root_type     varchar(32) NOT NULL,
    root_id       uuid NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'pending',
    attempts      integer NOT NULL DEFAULT 0,
    last_error    text,
    next_retry_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_cleanup_jobs_root UNIQUE (root_type, root_id),
    CONSTRAINT ck_cleanup_jobs_type CHECK (root_type IN ('submission', 'training_record', 'temp_attempt', 'material')),
    CONSTRAINT ck_cleanup_jobs_status CHECK (status IN ('pending', 'running', 'completed', 'failed')),
    CONSTRAINT ck_cleanup_jobs_attempts CHECK (attempts >= 0)
);
