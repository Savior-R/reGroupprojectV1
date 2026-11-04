-- M6 training tables. Cross-schema FKs are added in V10.
CREATE TABLE m6_training.training_templates (
    id                           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_task_id               uuid NOT NULL,
    source_task_config_version   integer NOT NULL,
    course_id                    uuid NOT NULL,
    training_level               varchar(16) NOT NULL,
    classroom_mode               varchar(16) NOT NULL DEFAULT 'free',
    config_snapshot              jsonb NOT NULL,
    created_by                   uuid NOT NULL,
    status                       varchar(16) NOT NULL DEFAULT 'published',
    published_at                 timestamptz,
    unpublished_at               timestamptz,
    created_at                   timestamptz NOT NULL DEFAULT now(),
    updated_at                   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_templates_level CHECK (training_level IN ('progressive', 'mock_exam')),
    CONSTRAINT ck_training_templates_mode CHECK (classroom_mode IN ('free')),
    CONSTRAINT ck_training_templates_status CHECK (status IN ('published', 'unpublished'))
);

CREATE TABLE m6_training.training_session_meta (
    session_id                uuid PRIMARY KEY,
    template_id               uuid,
    training_level            varchar(16) NOT NULL,
    effective_config_snapshot jsonb NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_session_meta_level CHECK (training_level IN ('progressive', 'mock_exam'))
);

CREATE TABLE m6_training.training_records (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    training_session_id uuid NOT NULL,
    student_account_id  uuid NOT NULL,
    template_id         uuid,
    selected_at         timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'selected',
    deleted_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_training_records_session UNIQUE (training_session_id),
    CONSTRAINT ck_training_records_status CHECK (status IN ('selected', 'evaluated', 'deleted', 'expired')),
    CONSTRAINT ck_training_records_expires CHECK (expires_at > selected_at)
);

CREATE TABLE m6_training.training_materials (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    student_account_id uuid NOT NULL,
    filename           varchar(255) NOT NULL,
    mime               varchar(100) NOT NULL,
    size               bigint NOT NULL,
    object_key         varchar(512) NOT NULL,
    parsed_text        text,
    char_count         integer,
    status             varchar(16) NOT NULL DEFAULT 'uploaded',
    expires_at         timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_materials_status CHECK (status IN ('uploaded', 'used', 'deleted', 'expired')),
    CONSTRAINT ck_training_materials_size CHECK (size > 0),
    CONSTRAINT ck_training_materials_chars CHECK (char_count IS NULL OR char_count >= 0)
);
