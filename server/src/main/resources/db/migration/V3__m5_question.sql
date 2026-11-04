-- M5 question bank tables. Cross-schema FKs are added in V10.
CREATE TABLE m5_question.libraries (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id    uuid NOT NULL UNIQUE,
    seed_version varchar(32) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE m5_question.categories (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id   uuid NOT NULL,
    name        varchar(50) NOT NULL,
    sort_order  integer NOT NULL DEFAULT 0,
    status      varchar(16) NOT NULL DEFAULT 'active',
    created_by  uuid,
    updated_by  uuid,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_school_name UNIQUE (school_id, name),
    CONSTRAINT ck_categories_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_categories_name CHECK (length(btrim(name)) > 0)
);

CREATE TABLE m5_question.questions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     uuid NOT NULL,
    category_id   uuid NOT NULL,
    stem          varchar(500) NOT NULL,
    answer_points varchar(2000) NOT NULL,
    status        varchar(16) NOT NULL DEFAULT 'active',
    created_by    uuid,
    updated_by    uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_questions_status CHECK (status IN ('active', 'inactive')),
    CONSTRAINT ck_questions_content CHECK (length(btrim(stem)) > 0 AND length(btrim(answer_points)) > 0)
);
