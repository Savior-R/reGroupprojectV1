-- M6 personal training templates and course publications.
--
-- Before V16, m6_training.training_templates was a teacher publication tied to
-- (source_task_id, course_id, training_level). V16 turns it into a per-account
-- personal template library and moves course visibility to
-- m6_training.training_template_publications.
--
-- Historical template rows keep source_type='task' for provenance. New templates
-- are created from scratch (source_type='manual') or copied from a teacher
-- publication (source_type='template').

CREATE TABLE m6_training.training_template_publications (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id      uuid NOT NULL,
    course_id        uuid NOT NULL,
    status           varchar(16) NOT NULL DEFAULT 'published',
    published_at     timestamptz NOT NULL DEFAULT now(),
    unpublished_at   timestamptz,
    unpublish_reason varchar(32),
    created_by       uuid NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_template_publications_status
        CHECK (status IN ('published', 'unpublished')),
    CONSTRAINT ck_training_template_publications_unpublish
        CHECK (
            (status = 'published' AND unpublished_at IS NULL AND unpublish_reason IS NULL)
            OR (status = 'unpublished' AND unpublished_at IS NOT NULL AND unpublish_reason IS NOT NULL)
        ),
    CONSTRAINT ck_training_template_publications_reason
        CHECK (unpublish_reason IS NULL OR unpublish_reason IN (
            'manual', 'course_archived', 'teacher_removed', 'template_archived'
        )),
    CONSTRAINT fk_training_template_publications_template
        FOREIGN KEY (template_id) REFERENCES m6_training.training_templates (id) ON DELETE RESTRICT,
    CONSTRAINT fk_training_template_publications_course
        FOREIGN KEY (course_id) REFERENCES m2_course.courses (id) ON DELETE RESTRICT,
    CONSTRAINT fk_training_template_publications_creator
        FOREIGN KEY (created_by) REFERENCES m1_auth.accounts (id) ON DELETE RESTRICT
);

-- Backfill one publication per historical template row. A temporary column
-- carries the generated publication id so session/record rows can be linked.
ALTER TABLE m6_training.training_templates
    ADD COLUMN legacy_publication_id uuid;

UPDATE m6_training.training_templates
SET legacy_publication_id = gen_random_uuid();

INSERT INTO m6_training.training_template_publications
    (id, template_id, course_id, status, published_at, unpublished_at,
     unpublish_reason, created_by, created_at, updated_at)
SELECT
    t.legacy_publication_id,
    t.id,
    t.course_id,
    CASE WHEN t.status = 'published' THEN 'published' ELSE 'unpublished' END,
    COALESCE(t.published_at, t.created_at),
    CASE WHEN t.status = 'unpublished'
         THEN COALESCE(t.unpublished_at, t.updated_at) ELSE NULL END,
    CASE WHEN t.status = 'unpublished' THEN 'manual' ELSE NULL END,
    t.created_by,
    t.created_at,
    t.updated_at
FROM m6_training.training_templates t;

ALTER TABLE m6_training.training_session_meta
    ADD COLUMN publication_id uuid;

UPDATE m6_training.training_session_meta sm
SET publication_id = t.legacy_publication_id
FROM m6_training.training_templates t
WHERE sm.template_id = t.id;

ALTER TABLE m6_training.training_records
    ADD COLUMN publication_id uuid;

UPDATE m6_training.training_records r
SET publication_id = sm.publication_id
FROM m6_training.training_session_meta sm
WHERE sm.session_id = r.training_session_id;

-- Turn training_templates into the personal template library.
ALTER TABLE m6_training.training_templates
    ADD COLUMN school_id uuid,
    ADD COLUMN owner_role varchar(16),
    ADD COLUMN name varchar(100),
    ADD COLUMN source_type varchar(16),
    ADD COLUMN source_template_id uuid,
    ADD COLUMN source_publication_id uuid,
    ADD COLUMN archived_at timestamptz;

-- Drop the old published/unpublished check before rewriting status to active.
ALTER TABLE m6_training.training_templates
    DROP CONSTRAINT ck_training_templates_status;

UPDATE m6_training.training_templates t
SET owner_role = 'teacher',
    name = left(COALESCE(NULLIF(btrim(t.config_snapshot ->> 'topic'), ''), '历史训练模板'), 100),
    source_type = 'task',
    school_id = COALESCE(
        (SELECT a.school_id FROM m1_auth.accounts a WHERE a.id = t.created_by),
        (SELECT c.school_id FROM m2_course.courses c WHERE c.id = t.course_id),
        (SELECT c2.school_id
           FROM m3_classroom.tasks tk
           JOIN m2_course.courses c2 ON c2.id = tk.course_id
          WHERE tk.id = t.source_task_id)
    ),
    status = 'active',
    archived_at = NULL;

ALTER TABLE m6_training.training_templates
    DROP CONSTRAINT fk_training_templates_course;
ALTER TABLE m6_training.training_templates
    DROP CONSTRAINT fk_training_templates_creator;

DROP INDEX m6_training.uq_training_templates_published;
DROP INDEX m6_training.idx_training_templates_course;

ALTER TABLE m6_training.training_templates
    RENAME COLUMN created_by TO owner_account_id;

ALTER TABLE m6_training.training_templates
    DROP COLUMN course_id,
    DROP COLUMN published_at,
    DROP COLUMN unpublished_at,
    DROP COLUMN legacy_publication_id;

ALTER TABLE m6_training.training_templates
    ALTER COLUMN source_task_id DROP NOT NULL,
    ALTER COLUMN source_task_config_version DROP NOT NULL,
    ALTER COLUMN status SET DEFAULT 'active';

ALTER TABLE m6_training.training_templates
    ALTER COLUMN school_id SET NOT NULL,
    ALTER COLUMN owner_account_id SET NOT NULL,
    ALTER COLUMN owner_role SET NOT NULL,
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN source_type SET NOT NULL;

ALTER TABLE m6_training.training_templates
    ADD CONSTRAINT ck_training_templates_owner_role
        CHECK (owner_role IN ('teacher', 'student')),
    ADD CONSTRAINT ck_training_templates_source_type
        CHECK (source_type IN ('manual', 'task', 'template')),
    ADD CONSTRAINT ck_training_templates_status
        CHECK (status IN ('active', 'archived')),
    ADD CONSTRAINT ck_training_templates_archive
        CHECK (
            (status = 'active' AND archived_at IS NULL)
            OR (status = 'archived' AND archived_at IS NOT NULL)
        ),
    ADD CONSTRAINT ck_training_templates_name
        CHECK (length(btrim(name)) > 0),
    ADD CONSTRAINT ck_training_templates_source
        CHECK (
            (source_type = 'manual'
                AND source_task_id IS NULL
                AND source_template_id IS NULL
                AND source_publication_id IS NULL)
            OR (source_type = 'task'
                AND source_task_id IS NOT NULL
                AND source_template_id IS NULL
                AND source_publication_id IS NULL)
            OR (source_type = 'template'
                AND source_task_id IS NULL
                AND source_template_id IS NOT NULL
                AND source_publication_id IS NOT NULL)
        ),
    ADD CONSTRAINT ck_training_templates_owner_source
        CHECK (
            (owner_role = 'teacher' AND source_type IN ('manual', 'task'))
            OR (owner_role = 'student' AND source_type IN ('manual', 'template'))
        ),
    ADD CONSTRAINT ck_training_templates_copy_self
        CHECK (source_template_id IS NULL OR source_template_id <> id);

ALTER TABLE m6_training.training_templates
    ADD CONSTRAINT fk_training_templates_school
        FOREIGN KEY (school_id) REFERENCES m1_auth.schools (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_training_templates_owner
        FOREIGN KEY (owner_account_id) REFERENCES m1_auth.accounts (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_training_templates_source_template
        FOREIGN KEY (source_template_id) REFERENCES m6_training.training_templates (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_training_templates_source_publication
        FOREIGN KEY (source_publication_id)
            REFERENCES m6_training.training_template_publications (id) ON DELETE RESTRICT;

CREATE INDEX idx_training_templates_owner_status
    ON m6_training.training_templates (owner_account_id, status, updated_at DESC);
CREATE INDEX idx_training_templates_school_owner
    ON m6_training.training_templates (school_id, owner_account_id);
CREATE INDEX idx_training_templates_source_template
    ON m6_training.training_templates (source_template_id);
CREATE INDEX idx_training_templates_source_publication
    ON m6_training.training_templates (source_publication_id);

CREATE UNIQUE INDEX uq_training_template_publications_published
    ON m6_training.training_template_publications (template_id, course_id)
    WHERE status = 'published';
CREATE INDEX idx_training_template_publications_course
    ON m6_training.training_template_publications (course_id, status);
CREATE INDEX idx_training_template_publications_template
    ON m6_training.training_template_publications (template_id, status);
CREATE INDEX idx_training_template_publications_creator
    ON m6_training.training_template_publications (created_by, status);

ALTER TABLE m6_training.training_session_meta
    ADD CONSTRAINT fk_training_meta_publication
        FOREIGN KEY (publication_id)
            REFERENCES m6_training.training_template_publications (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_training_session_meta_publication
        CHECK (publication_id IS NULL OR template_id IS NOT NULL);

CREATE INDEX idx_training_session_meta_template
    ON m6_training.training_session_meta (template_id);
CREATE INDEX idx_training_session_meta_publication
    ON m6_training.training_session_meta (publication_id);

ALTER TABLE m6_training.training_records
    ADD CONSTRAINT fk_training_records_publication
        FOREIGN KEY (publication_id)
            REFERENCES m6_training.training_template_publications (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_training_records_publication
        CHECK (publication_id IS NULL OR template_id IS NOT NULL);

CREATE INDEX idx_training_records_publication
    ON m6_training.training_records (publication_id);

-- Training materials serve both free sessions and persistent template materials.
ALTER TABLE m6_training.training_materials
    RENAME COLUMN student_account_id TO owner_account_id;
ALTER TABLE m6_training.training_materials
    RENAME CONSTRAINT fk_training_materials_student TO fk_training_materials_owner;
ALTER TABLE m6_training.training_materials
    ADD COLUMN usage varchar(16) NOT NULL DEFAULT 'free_session',
    ADD COLUMN template_id uuid,
    ALTER COLUMN expires_at DROP NOT NULL;

ALTER TABLE m6_training.training_materials
    DROP CONSTRAINT ck_training_materials_status;
ALTER TABLE m6_training.training_materials
    ADD CONSTRAINT ck_training_materials_status
        CHECK (status IN ('uploaded', 'used', 'attached', 'deleted', 'expired')),
    ADD CONSTRAINT ck_training_materials_usage
        CHECK (usage IN ('free_session', 'template')),
    ADD CONSTRAINT ck_training_materials_usage_expiry
        CHECK (
            (usage = 'free_session' AND expires_at IS NOT NULL)
            OR (usage = 'template' AND (
                (status = 'attached' AND expires_at IS NULL)
                OR (status = 'uploaded' AND expires_at IS NOT NULL)
                OR status IN ('deleted', 'expired')
            ))
        ),
    ADD CONSTRAINT ck_training_materials_template_attached
        CHECK (status <> 'attached' OR (usage = 'template' AND template_id IS NOT NULL)),
    ADD CONSTRAINT fk_training_materials_template
        FOREIGN KEY (template_id) REFERENCES m6_training.training_templates (id) ON DELETE SET NULL;

DROP INDEX m6_training.idx_training_materials_student;
CREATE INDEX idx_training_materials_owner
    ON m6_training.training_materials (owner_account_id, usage, status);
CREATE INDEX idx_training_materials_template
    ON m6_training.training_materials (template_id);

-- Provenance only: the task draft copied its initial config from an M6 template.
-- No FK on purpose, because M6.training_templates keeps a legacy source_task_id
-- reference to m3_classroom.tasks.
ALTER TABLE m3_classroom.tasks
    ADD COLUMN source_training_template_id uuid;

CREATE INDEX idx_tasks_source_training_template
    ON m3_classroom.tasks (source_training_template_id);

COMMENT ON COLUMN m3_classroom.tasks.source_training_template_id IS
    'M6 personal template used to prefill this task draft; opaque reference, no FK';

-- Permission catalog for the personal-template and publication endpoints.
-- The existing V12 row for /api/v1/student/training/templates/{template_id}
-- also matches the new {publication_id} URL shape; no duplicate row is added.
WITH routes(module, protocol, method, path_pattern, perm_code) AS (
    VALUES
        ('M6', 'REST', 'GET',    '/api/v1/training/my-templates', 'training:template:personal'),
        ('M6', 'REST', 'POST',   '/api/v1/training/my-templates', 'training:template:personal'),
        ('M6', 'REST', 'GET',    '/api/v1/training/my-templates/{template_id}', 'training:template:personal'),
        ('M6', 'REST', 'PATCH',  '/api/v1/training/my-templates/{template_id}', 'training:template:personal'),
        ('M6', 'REST', 'POST',   '/api/v1/training/my-templates/{template_id}/archive', 'training:template:personal'),
        ('M6', 'REST', 'POST',   '/api/v1/training/my-templates/{template_id}/restore', 'training:template:personal'),
        ('M6', 'REST', 'POST',   '/api/v1/training/my-templates/from-publication', 'training:template:personal'),
        ('M6', 'REST', 'GET',    '/api/v1/training/my-templates/{template_id}/task-config', 'training:template:personal'),
        ('M6', 'REST', 'POST',   '/api/v1/training/template-materials', 'training:template:personal'),
        ('M6', 'REST', 'DELETE', '/api/v1/training/template-materials/{material_id}', 'training:template:personal'),
        ('M6', 'REST', 'GET',    '/api/v1/training/publications', 'training:template:manage'),
        ('M6', 'REST', 'POST',   '/api/v1/training/my-templates/{template_id}/publications', 'training:template:manage'),
        ('M6', 'REST', 'POST',   '/api/v1/training/publications/{publication_id}/unpublish', 'training:template:manage')
),
ranked AS (
    SELECT *,
           row_number() OVER (
               PARTITION BY perm_code
               ORDER BY method, path_pattern
           ) AS rn
    FROM routes
)
INSERT INTO m1_auth.api_permissions
    (code, name, module, protocol, method, path_pattern, description)
SELECT
    CASE
        WHEN r.perm_code = 'training:template:personal' AND r.rn = 1 THEN r.perm_code
        ELSE r.perm_code || '#m6p' || r.rn::text
    END,
    r.module || ' ' || r.perm_code,
    r.module,
    r.protocol,
    r.method,
    r.path_pattern,
    r.perm_code
FROM ranked r
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.account_type_permissions (account_type_id, permission_id)
SELECT at.id, p.id
FROM m1_auth.account_types at
JOIN m1_auth.api_permissions p ON (
    (at.code = 'student' AND p.description IN (
        'training:template:personal', 'training:template:read'
    ))
    OR (at.code = 'teacher' AND p.description IN (
        'training:template:personal', 'training:template:read', 'training:template:manage'
    ))
)
ON CONFLICT DO NOTHING;
