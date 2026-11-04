-- M7 teacher grading / final score tables. Cross-schema FKs are added in V10.
CREATE TABLE m7_teacher.task_grading_configs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id        uuid NOT NULL UNIQUE,
    course_id      uuid NOT NULL,
    ai_weight      integer NOT NULL DEFAULT 70,
    teacher_weight integer NOT NULL DEFAULT 30,
    scoring_mode   varchar(16) NOT NULL DEFAULT 'dimension',
    config_version integer NOT NULL DEFAULT 1,
    locked_at      timestamptz,
    updated_by     uuid NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_grading_weights CHECK (
        ai_weight BETWEEN 0 AND 100
        AND teacher_weight BETWEEN 0 AND 100
        AND ai_weight + teacher_weight = 100
    ),
    CONSTRAINT ck_grading_mode CHECK (scoring_mode IN ('dimension', 'total')),
    CONSTRAINT ck_grading_version CHECK (config_version > 0)
);

CREATE TABLE m7_teacher.teacher_scores (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id      uuid NOT NULL UNIQUE,
    task_id            uuid NOT NULL,
    course_id          uuid NOT NULL,
    student_account_id uuid NOT NULL,
    class_id_snapshot  uuid,
    scoring_mode       varchar(16) NOT NULL,
    dimension_scores   jsonb,
    teacher_total      numeric(5,2) NOT NULL,
    comment            varchar(1000),
    version            integer NOT NULL DEFAULT 1,
    graded_by          uuid NOT NULL,
    graded_at          timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_teacher_scores_mode CHECK (scoring_mode IN ('dimension', 'total')),
    CONSTRAINT ck_teacher_scores_total CHECK (teacher_total BETWEEN 0 AND 100),
    CONSTRAINT ck_teacher_scores_version CHECK (version > 0)
);

CREATE TABLE m7_teacher.final_scores (
    submission_id      uuid PRIMARY KEY,
    task_id            uuid NOT NULL,
    course_id          uuid NOT NULL,
    student_account_id uuid NOT NULL,
    ai_evaluation_id   uuid,
    teacher_score_id   uuid,
    ai_total           numeric(5,2),
    teacher_total      numeric(5,2),
    final_total        numeric(5,2),
    final_dimensions   jsonb,
    status             varchar(16) NOT NULL,
    class_id_snapshot  uuid,
    config_version     integer NOT NULL,
    error_code         varchar(64),
    error_message      text,
    computed_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_final_scores_status CHECK (status IN ('pending_ai', 'pending_teacher', 'completed', 'ai_partial', 'ai_failed')),
    CONSTRAINT ck_final_scores_total CHECK (final_total IS NULL OR (final_total BETWEEN 0 AND 100)),
    CONSTRAINT ck_final_scores_completed CHECK (
        (status = 'completed' AND final_total IS NOT NULL AND final_dimensions IS NOT NULL)
        OR (status <> 'completed' AND final_total IS NULL AND final_dimensions IS NULL)
    )
);
