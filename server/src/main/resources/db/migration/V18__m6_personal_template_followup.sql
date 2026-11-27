-- M6 V16 follow-up: align the publication unpublish reason and the personal-template
-- delete permission with 08 §4.3 / §10.1.
--
-- V16 used 'template_archived' for the automatic take-down, but the published contract
-- (and TrainingUnpublishReason) uses 'template_deleted'. V16 also catalogued
-- /archive and /restore routes that the V1.5 UI does not expose; the real operation is
-- DELETE /api/v1/training/my-templates/{template_id}.

ALTER TABLE m6_training.training_template_publications
    DROP CONSTRAINT ck_training_template_publications_reason;

UPDATE m6_training.training_template_publications
SET unpublish_reason = 'template_deleted'
WHERE unpublish_reason = 'template_archived';

ALTER TABLE m6_training.training_template_publications
    ADD CONSTRAINT ck_training_template_publications_reason
        CHECK (unpublish_reason IS NULL OR unpublish_reason IN (
            'manual', 'course_archived', 'teacher_removed', 'template_deleted'
        ));

INSERT INTO m1_auth.api_permissions
    (code, name, module, protocol, method, path_pattern, description)
VALUES
    ('training:template:personal#m6p-del',
     'M6 training:template:personal',
     'M6',
     'REST',
     'DELETE',
     '/api/v1/training/my-templates/{template_id}',
     'training:template:personal')
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.account_type_permissions (account_type_id, permission_id)
SELECT at.id, p.id
FROM m1_auth.account_types at
JOIN m1_auth.api_permissions p
  ON p.method = 'DELETE'
 AND p.path_pattern = '/api/v1/training/my-templates/{template_id}'
WHERE at.code IN ('student', 'teacher')
ON CONFLICT DO NOTHING;
