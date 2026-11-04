-- M4 evaluation tables. Cross-schema FKs are added in V10.
CREATE TABLE m4_eval.evaluations (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type            varchar(32) NOT NULL,
    source_id              uuid NOT NULL,
    submission_id          uuid,
    training_record_id     uuid,
    session_id             uuid NOT NULL,
    task_id                uuid,
    course_id              uuid,
    student_account_id     uuid NOT NULL,
    mode                   varchar(16) NOT NULL,
    status                 varchar(16) NOT NULL DEFAULT 'pending',
    total_score            numeric(5,2),
    weights                jsonb NOT NULL DEFAULT '{}'::jsonb,
    task_config_version    integer,
    training_config_version integer,
    model_version          varchar(64),
    prompt_version         varchar(64),
    rubric_version         varchar(64),
    attempt_count          integer NOT NULL DEFAULT 0,
    warnings               jsonb NOT NULL DEFAULT '[]'::jsonb,
    partial_reasons        jsonb NOT NULL DEFAULT '[]'::jsonb,
    error_code             varchar(64),
    error_message          text,
    token_usage            jsonb,
    started_at             timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    completed_at           timestamptz,
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_evaluations_source UNIQUE (source_type, source_id),
    CONSTRAINT ck_evaluations_source CHECK (
        (source_type = 'task_submission' AND submission_id IS NOT NULL AND training_record_id IS NULL)
        OR (source_type = 'training_record' AND training_record_id IS NOT NULL AND submission_id IS NULL)
    ),
    CONSTRAINT ck_evaluations_mode CHECK (mode IN ('free', 'scenario', 'interview')),
    CONSTRAINT ck_evaluations_status CHECK (status IN ('pending', 'running', 'completed', 'partial', 'failed')),
    CONSTRAINT ck_evaluations_total CHECK (
        (status = 'completed' AND total_score IS NOT NULL)
        OR (status <> 'completed' AND total_score IS NULL)
    ),
    CONSTRAINT ck_evaluations_score CHECK (total_score IS NULL OR (total_score BETWEEN 0 AND 100)),
    CONSTRAINT ck_evaluations_attempts CHECK (attempt_count >= 0)
);

CREATE TABLE m4_eval.dimension_scores (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id    uuid NOT NULL,
    dimension        varchar(32) NOT NULL,
    score            numeric(5,2) NOT NULL,
    sub_scores       jsonb NOT NULL DEFAULT '{}'::jsonb,
    sub_confidences  jsonb NOT NULL DEFAULT '{}'::jsonb,
    confidence       numeric(3,2) NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dimension_scores UNIQUE (evaluation_id, dimension),
    CONSTRAINT ck_dimension_scores_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_dimension_scores_conf CHECK (confidence BETWEEN 0 AND 1)
);

CREATE TABLE m4_eval.issues (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id    uuid NOT NULL,
    dimension        varchar(32) NOT NULL,
    start_ms         bigint,
    end_ms           bigint,
    severity         varchar(16) NOT NULL,
    evidence         varchar(500) NOT NULL,
    suggestion       varchar(500) NOT NULL,
    is_suggestion    boolean NOT NULL DEFAULT false,
    suggestion_rank  integer,
    sort_order       integer NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_issues_severity CHECK (severity IN ('high', 'medium', 'low')),
    CONSTRAINT ck_issues_time CHECK (start_ms IS NULL OR end_ms IS NULL OR start_ms <= end_ms),
    CONSTRAINT ck_issues_rank CHECK (suggestion_rank IS NULL OR suggestion_rank > 0)
);

CREATE TABLE m4_eval.input_snapshots (
    evaluation_id           uuid PRIMARY KEY,
    source_type             varchar(32) NOT NULL,
    source_id               uuid NOT NULL,
    submission_id           uuid,
    training_record_id      uuid,
    recording_ids           jsonb NOT NULL DEFAULT '[]'::jsonb,
    text_ids                jsonb NOT NULL DEFAULT '[]'::jsonb,
    frame_ids               jsonb NOT NULL DEFAULT '[]'::jsonb,
    event_ids               jsonb NOT NULL DEFAULT '[]'::jsonb,
    config_version          integer NOT NULL,
    task_config_version     integer,
    training_config_version integer,
    flags                   jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE m4_eval.evaluation_jobs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id  uuid NOT NULL,
    status         varchar(16) NOT NULL DEFAULT 'queued',
    attempts       integer NOT NULL DEFAULT 0,
    last_error     text,
    next_retry_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_evaluation_jobs UNIQUE (evaluation_id),
    CONSTRAINT ck_evaluation_jobs_status CHECK (status IN ('queued', 'running', 'completed', 'partial', 'failed')),
    CONSTRAINT ck_evaluation_jobs_attempts CHECK (attempts >= 0)
);
