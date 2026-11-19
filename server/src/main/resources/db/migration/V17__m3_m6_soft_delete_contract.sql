-- V17: M3 task no-draft / soft-delete.
--
-- M3: tasks are created published. Existing draft rows are preserved as soft
-- deleted so no demo data is lost and they stay hidden from student/teacher
-- lists. `deleted_by` is the original creator for migrated drafts.

ALTER TABLE m3_classroom.tasks
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by uuid;

ALTER TABLE m3_classroom.tasks
    DROP CONSTRAINT ck_tasks_status;

UPDATE m3_classroom.tasks
SET status = 'deleted',
    deleted_at = now(),
    deleted_by = created_by,
    config_version = GREATEST(config_version, 1)
WHERE status = 'draft';

UPDATE m3_classroom.tasks
SET config_version = GREATEST(config_version, 1);

ALTER TABLE m3_classroom.tasks
    ADD CONSTRAINT ck_tasks_status CHECK (status IN ('published', 'closed', 'deleted')),
    DROP CONSTRAINT ck_tasks_config_version,
    ADD CONSTRAINT ck_tasks_config_version CHECK (config_version >= 1),
    ALTER COLUMN config_version SET DEFAULT 1,
    ADD CONSTRAINT ck_tasks_deleted CHECK (
        (status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL)
    );

COMMENT ON COLUMN m3_classroom.tasks.deleted_at IS
    'Soft-delete time; only set when status=deleted';
COMMENT ON COLUMN m3_classroom.tasks.deleted_by IS
    'Account that soft-deleted the task; no FK to keep audit history after account cleanup';
