-- M3 classroom / submission / recording / context tables. FKs are added in V10.
CREATE TABLE m3_classroom.tasks (
    id                           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id                    uuid NOT NULL,
    mode                         varchar(16) NOT NULL,
    topic                        varchar(200) NOT NULL,
    textbook_version             varchar(100),
    reference_material           jsonb,
    scenario_config              jsonb,
    duration_recommended_seconds integer NOT NULL,
    duration_max_seconds         integer NOT NULL,
    rubric_weights               jsonb NOT NULL DEFAULT '{}'::jsonb,
    question_config              jsonb,
    config_snapshot              jsonb,
    config_version               integer NOT NULL DEFAULT 0,
    status                       varchar(16) NOT NULL DEFAULT 'draft',
    created_by                   uuid NOT NULL,
    published_at                 timestamptz,
    closed_at                    timestamptz,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_tasks_mode CHECK (mode IN ('free', 'scenario', 'interview')),
    CONSTRAINT ck_tasks_status CHECK (status IN ('draft', 'published', 'closed')),
    CONSTRAINT ck_tasks_duration CHECK (
        duration_recommended_seconds BETWEEN 1 AND 3600
        AND duration_max_seconds BETWEEN 1 AND 3600
        AND duration_recommended_seconds <= duration_max_seconds
    ),
    CONSTRAINT ck_tasks_config_version CHECK (config_version >= 0),
    CONSTRAINT ck_tasks_topic CHECK (length(btrim(topic)) > 0)
);

CREATE TABLE m3_classroom.task_assignments (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id            uuid NOT NULL,
    student_account_id uuid NOT NULL,
    class_id_snapshot  uuid,
    assigned_at        timestamptz NOT NULL DEFAULT now(),
    status             varchar(16) NOT NULL DEFAULT 'assigned',
    CONSTRAINT uq_task_assignments UNIQUE (task_id, student_account_id),
    CONSTRAINT ck_task_assignments_status CHECK (status IN ('assigned', 'submitted'))
);

CREATE TABLE m3_classroom.virtual_students (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id               uuid NOT NULL,
    name                  varchar(20) NOT NULL,
    avatar                varchar(64) NOT NULL,
    personality_text      varchar(500) NOT NULL,
    activity              numeric(3,2) NOT NULL DEFAULT 0.50,
    question_rate         numeric(3,2) NOT NULL DEFAULT 0.50,
    ability               numeric(3,2) NOT NULL DEFAULT 0.50,
    attention_baseline    numeric(3,2) NOT NULL DEFAULT 0.50,
    discipline_probability numeric(3,2) NOT NULL DEFAULT 0.50,
    seat_no               integer NOT NULL,
    sort_order            integer NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_virtual_students_params CHECK (
        activity BETWEEN 0 AND 1
        AND question_rate BETWEEN 0 AND 1
        AND ability BETWEEN 0 AND 1
        AND attention_baseline BETWEEN 0 AND 1
        AND discipline_probability BETWEEN 0 AND 1
    )
);

CREATE TABLE m3_classroom.sessions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_type       varchar(16) NOT NULL,
    task_id            uuid,
    course_id          uuid,
    student_account_id uuid NOT NULL,
    mode               varchar(16) NOT NULL,
    status             varchar(32) NOT NULL DEFAULT 'preparing',
    attempt_no         integer NOT NULL DEFAULT 1,
    started_at         timestamptz,
    ended_at           timestamptz,
    duration_ms        bigint,
    stream_offsets     jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sessions_type CHECK (session_type IN ('task', 'training')),
    CONSTRAINT ck_sessions_mode CHECK (mode IN ('free', 'scenario', 'interview')),
    CONSTRAINT ck_sessions_status CHECK (status IN (
        'preparing', 'in_progress', 'ended', 'reviewing', 'submitted',
        'selected', 'evaluated', 'cancelled', 'failed'
    )),
    CONSTRAINT ck_sessions_type_task CHECK (
        (session_type = 'training' AND task_id IS NULL)
        OR (session_type = 'task' AND task_id IS NOT NULL)
    ),
    CONSTRAINT ck_sessions_terminal CHECK (
        (session_type = 'task' AND status NOT IN ('selected', 'evaluated'))
        OR (session_type = 'training' AND status <> 'submitted')
    ),
    CONSTRAINT ck_sessions_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE TABLE m3_classroom.submissions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id            uuid NOT NULL,
    student_account_id uuid NOT NULL,
    session_id         uuid NOT NULL,
    class_id_snapshot  uuid,
    submitted_at       timestamptz NOT NULL DEFAULT now(),
    status             varchar(16) NOT NULL DEFAULT 'submitted',
    CONSTRAINT uq_submissions_task_student UNIQUE (task_id, student_account_id),
    CONSTRAINT uq_submissions_session UNIQUE (session_id),
    CONSTRAINT ck_submissions_status CHECK (status IN ('submitted'))
);

CREATE TABLE m3_classroom.recordings (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  uuid NOT NULL,
    duration_ms bigint,
    status      varchar(16) NOT NULL DEFAULT 'recording',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_recordings_session UNIQUE (session_id),
    CONSTRAINT ck_recordings_status CHECK (status IN ('recording', 'uploading', 'uploaded', 'failed', 'deleted')),
    CONSTRAINT ck_recordings_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);

CREATE TABLE m3_classroom.recording_streams (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recording_id   uuid NOT NULL,
    stream_type    varchar(16) NOT NULL,
    upload_id      varchar(255),
    object_key     varchar(512),
    part_size      bigint NOT NULL DEFAULT 5242880,
    parts_uploaded integer NOT NULL DEFAULT 0,
    bytes_uploaded bigint NOT NULL DEFAULT 0,
    duration_ms    bigint,
    offset_ms      bigint NOT NULL DEFAULT 0,
    mime           varchar(100),
    status         varchar(16) NOT NULL DEFAULT 'uploading',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_recording_streams UNIQUE (recording_id, stream_type),
    CONSTRAINT ck_recording_streams_type CHECK (stream_type IN ('camera', 'screen', 'audio')),
    CONSTRAINT ck_recording_streams_status CHECK (status IN ('uploading', 'uploaded', 'failed', 'deleted')),
    CONSTRAINT ck_recording_streams_counts CHECK (part_size > 0 AND parts_uploaded >= 0 AND bytes_uploaded >= 0)
);

CREATE TABLE m3_classroom.session_texts (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id     uuid NOT NULL,
    speaker_type   varchar(16) NOT NULL,
    speaker_ref    uuid,
    source         varchar(16) NOT NULL,
    kind           varchar(16) NOT NULL,
    text           text NOT NULL,
    start_ms       bigint,
    end_ms         bigint,
    at_ms          bigint NOT NULL,
    confidence     numeric(3,2),
    model_version  varchar(64),
    prompt_version varchar(64),
    status         varchar(16) NOT NULL DEFAULT 'final',
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_session_texts_speaker CHECK (speaker_type IN ('teacher', 'student', 'examiner', 'candidate')),
    CONSTRAINT ck_session_texts_source CHECK (source IN ('asr', 'llm', 'manual')),
    CONSTRAINT ck_session_texts_kind CHECK (kind IN ('utterance', 'question', 'answer', 'follow_up')),
    CONSTRAINT ck_session_texts_status CHECK (status IN ('partial', 'final')),
    CONSTRAINT ck_session_texts_confidence CHECK (confidence IS NULL OR (confidence BETWEEN 0 AND 1))
);

CREATE TABLE m3_classroom.frames (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id      uuid NOT NULL,
    timestamp_ms    bigint NOT NULL,
    text            text,
    bounding_boxes  jsonb,
    confidence      numeric(3,2),
    change_score    numeric(5,4),
    frame_type      varchar(24) NOT NULL DEFAULT 'normal',
    object_key      varchar(512),
    content_hash    varchar(64),
    status          varchar(16) NOT NULL DEFAULT 'pending',
    error_message   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_frames_type CHECK (frame_type IN ('normal', 'slide_change', 'final', 'low_confidence', 'chart')),
    CONSTRAINT ck_frames_status CHECK (status IN ('pending', 'processed', 'failed')),
    CONSTRAINT ck_frames_confidence CHECK (confidence IS NULL OR (confidence BETWEEN 0 AND 1))
);

CREATE TABLE m3_classroom.events (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id    uuid NOT NULL,
    seq           bigint NOT NULL,
    type          varchar(64) NOT NULL,
    timestamp_ms  bigint NOT NULL,
    actor         varchar(32),
    target        varchar(64),
    payload       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_events_session_seq UNIQUE (session_id, seq),
    CONSTRAINT ck_events_seq CHECK (seq > 0)
);
