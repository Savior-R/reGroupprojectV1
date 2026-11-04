-- System seed: account types, API permission catalog, and role mapping.
-- One api_permissions row per route. When a 02 permission code covers multiple
-- routes, the code is suffixed with #<n>; the original code is kept in description.

INSERT INTO m1_auth.account_types (id, code, name) VALUES
  ('00000000-0000-0000-0000-000000000101', 'student', '学生'),
  ('00000000-0000-0000-0000-000000000102', 'teacher', '教师'),
  ('00000000-0000-0000-0000-000000000103', 'admin', '管理员')
ON CONFLICT DO NOTHING;

WITH routes(module, protocol, method, path_pattern, perm_code) AS (
  VALUES
    ('M1', 'REST', 'POST',   '/api/v1/auth/register', 'public'),
    ('M1', 'REST', 'POST',   '/api/v1/auth/login', 'public'),
    ('M1', 'REST', 'GET',    '/api/v1/auth/me', 'login'),
    ('M1', 'REST', 'GET',    '/api/v1/public/schools', 'public'),
    ('M1', 'REST', 'GET',    '/api/v1/public/schools/{school_id}/majors', 'public'),
    ('M1', 'REST', 'GET',    '/api/v1/public/schools/{school_id}/grades', 'public'),
    ('M1', 'REST', 'GET',    '/api/v1/public/classes', 'public'),
    ('M1', 'REST', 'POST',   '/api/v1/admin/schools', 'admin:school'),
    ('M1', 'REST', 'GET',    '/api/v1/admin/school', 'admin:school'),
    ('M1', 'REST', 'PATCH',  '/api/v1/admin/school', 'admin:school'),
    ('M1', 'REST', 'GET',    '/api/v1/admin/majors', 'admin:org'),
    ('M1', 'REST', 'POST',   '/api/v1/admin/majors', 'admin:org'),
    ('M1', 'REST', 'PATCH',  '/api/v1/admin/majors/{major_id}', 'admin:org'),
    ('M1', 'REST', 'POST',   '/api/v1/admin/majors/import', 'admin:org'),
    ('M1', 'REST', 'GET',    '/api/v1/admin/imports/templates/{type}', 'admin:org'),
    ('M1', 'REST', 'GET',    '/api/v1/admin/classes', 'admin:org'),
    ('M1', 'REST', 'POST',   '/api/v1/admin/classes', 'admin:org'),
    ('M1', 'REST', 'PATCH',  '/api/v1/admin/classes/{class_id}', 'admin:org'),
    ('M1', 'REST', 'POST',   '/api/v1/admin/classes/import', 'admin:org'),
    ('M1', 'REST', 'GET',    '/api/v1/admin/teachers', 'admin:teacher_majors'),
    ('M1', 'REST', 'PUT',    '/api/v1/admin/teachers/{account_id}/majors', 'admin:teacher_majors'),
    ('M1', 'REST', 'GET',    '/actuator/health', 'public'),

    ('M2', 'REST', 'GET',    '/api/v1/courses', 'course:read'),
    ('M2', 'REST', 'POST',   '/api/v1/courses', 'course:create'),
    ('M2', 'REST', 'GET',    '/api/v1/courses/{course_id}', 'course:read'),
    ('M2', 'REST', 'PATCH',  '/api/v1/courses/{course_id}', 'course:manage'),
    ('M2', 'REST', 'POST',   '/api/v1/courses/{course_id}/archive', 'course:archive'),
    ('M2', 'REST', 'POST',   '/api/v1/courses/{course_id}/restore', 'course:archive'),
    ('M2', 'REST', 'GET',    '/api/v1/courses/{course_id}/teachers', 'course:members:read'),
    ('M2', 'REST', 'POST',   '/api/v1/courses/{course_id}/teachers', 'course:members:manage'),
    ('M2', 'REST', 'DELETE', '/api/v1/courses/{course_id}/teachers/{account_id}', 'course:members:manage'),
    ('M2', 'REST', 'GET',    '/api/v1/courses/{course_id}/students', 'course:members:read'),
    ('M2', 'REST', 'POST',   '/api/v1/courses/{course_id}/students/class', 'course:members:manage'),
    ('M2', 'REST', 'POST',   '/api/v1/courses/{course_id}/students/account', 'course:members:manage'),
    ('M2', 'REST', 'DELETE', '/api/v1/courses/{course_id}/students/{account_id}', 'course:members:manage'),
    ('M2', 'REST', 'POST',   '/api/v1/courses/{course_id}/join', 'course:join'),
    ('M2', 'REST', 'POST',   '/api/v1/courses/{course_id}/leave', 'course:leave'),

    ('M3', 'REST', 'GET',    '/api/v1/student/tasks', 'task:read'),
    ('M3', 'REST', 'GET',    '/api/v1/student/tasks/{task_id}', 'task:read'),
    ('M3', 'REST', 'GET',    '/api/v1/courses/{course_id}/tasks', 'task:read'),
    ('M3', 'REST', 'POST',   '/api/v1/courses/{course_id}/tasks', 'task:create'),
    ('M3', 'REST', 'GET',    '/api/v1/tasks/{task_id}', 'task:read'),
    ('M3', 'REST', 'PATCH',  '/api/v1/tasks/{task_id}', 'task:create'),
    ('M3', 'REST', 'POST',   '/api/v1/tasks/{task_id}/publish', 'task:publish'),
    ('M3', 'REST', 'POST',   '/api/v1/tasks/{task_id}/close', 'task:close'),
    ('M3', 'REST', 'DELETE', '/api/v1/tasks/{task_id}', 'task:create'),
    ('M3', 'REST', 'POST',   '/api/v1/tasks/{task_id}/material', 'task:create'),
    ('M3', 'REST', 'GET',    '/api/v1/tasks/{task_id}/material', 'task:read'),
    ('M3', 'REST', 'DELETE', '/api/v1/tasks/{task_id}/material', 'task:create'),
    ('M3', 'REST', 'GET',    '/api/v1/tasks/{task_id}/assignments', 'task:assignments:read'),
    ('M3', 'REST', 'POST',   '/api/v1/tasks/{task_id}/sessions', 'session:start'),
    ('M3', 'REST', 'GET',    '/api/v1/sessions/{session_id}', 'session:read'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/start', 'session:start'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/end', 'session:start'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/cancel', 'session:start'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/submit', 'session:submit'),
    ('M3', 'REST', 'GET',    '/api/v1/sessions/{session_id}/replay', 'session:replay'),
    ('M3', 'REST', 'GET',    '/api/v1/sessions/{session_id}/events', 'session:events:read'),
    ('M3', 'REST', 'GET',    '/api/v1/sessions/{session_id}/texts', 'session:read'),
    ('M3', 'REST', 'GET',    '/api/v1/sessions/{session_id}/recordings', 'session:read'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/start', 'session:start'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/part-url', 'session:start'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/complete', 'session:start'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/recordings/{stream_type}/multipart/abort', 'session:start'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/ws-ticket', 'ws:session'),
    ('M3', 'REST', 'POST',   '/api/v1/sessions/{session_id}/perception/screen', 'session:start'),

    ('M4', 'REST', 'GET',    '/api/v1/submissions/{submission_id}/evaluation', 'evaluation:read'),
    ('M4', 'REST', 'GET',    '/api/v1/evaluations/{evaluation_id}', 'evaluation:read'),
    ('M4', 'REST', 'GET',    '/api/v1/evaluations/{evaluation_id}/report', 'evaluation:read'),
    ('M4', 'REST', 'GET',    '/api/v1/evaluations/{evaluation_id}/issues', 'evaluation:read'),
    ('M4', 'REST', 'GET',    '/api/v1/training/records/{record_id}/evaluation', 'evaluation:read'),

    ('M5', 'REST', 'GET',    '/api/v1/question-bank/categories', 'question_bank:read'),
    ('M5', 'REST', 'POST',   '/api/v1/question-bank/categories', 'question_bank:manage'),
    ('M5', 'REST', 'PATCH',  '/api/v1/question-bank/categories/{category_id}', 'question_bank:manage'),
    ('M5', 'REST', 'GET',    '/api/v1/question-bank/questions', 'question_bank:read'),
    ('M5', 'REST', 'POST',   '/api/v1/question-bank/questions', 'question_bank:manage'),
    ('M5', 'REST', 'PATCH',  '/api/v1/question-bank/questions/{question_id}', 'question_bank:manage'),
    ('M5', 'REST', 'POST',   '/api/v1/admin/question-bank/demo-init', 'question_bank:manage'),

    ('M6', 'REST', 'GET',    '/api/v1/training/templates', 'training:template:read'),
    ('M6', 'REST', 'POST',   '/api/v1/training/templates', 'training:template:manage'),
    ('M6', 'REST', 'GET',    '/api/v1/training/templates/{template_id}', 'training:template:read'),
    ('M6', 'REST', 'POST',   '/api/v1/training/templates/{template_id}/unpublish', 'training:template:manage'),
    ('M6', 'REST', 'GET',    '/api/v1/student/training/templates', 'training:template:read'),
    ('M6', 'REST', 'GET',    '/api/v1/student/training/templates/{template_id}', 'training:template:read'),
    ('M6', 'REST', 'POST',   '/api/v1/training/materials', 'training:session:start'),
    ('M6', 'REST', 'DELETE', '/api/v1/training/materials/{material_id}', 'training:session:start'),
    ('M6', 'REST', 'POST',   '/api/v1/training/sessions', 'training:session:start'),
    ('M6', 'REST', 'GET',    '/api/v1/training/sessions/{session_id}', 'training:session:start'),
    ('M6', 'REST', 'POST',   '/api/v1/training/sessions/{session_id}/end', 'training:session:start'),
    ('M6', 'REST', 'POST',   '/api/v1/training/sessions/{session_id}/cancel', 'training:session:start'),
    ('M6', 'REST', 'POST',   '/api/v1/training/records', 'training:record:manage'),
    ('M6', 'REST', 'GET',    '/api/v1/training/records', 'training:record:read'),
    ('M6', 'REST', 'GET',    '/api/v1/training/records/{record_id}', 'training:record:read'),
    ('M6', 'REST', 'DELETE', '/api/v1/training/records/{record_id}', 'training:record:manage'),

    ('M7', 'REST', 'GET',    '/api/v1/tasks/{task_id}/grading-config', 'teacher_eval:config'),
    ('M7', 'REST', 'PUT',    '/api/v1/tasks/{task_id}/grading-config', 'teacher_eval:config'),
    ('M7', 'REST', 'DELETE', '/api/v1/tasks/{task_id}/teacher-scores', 'teacher_eval:config'),
    ('M7', 'REST', 'GET',    '/api/v1/tasks/{task_id}/grading-submissions', 'teacher_eval:read'),
    ('M7', 'REST', 'PUT',    '/api/v1/submissions/{submission_id}/teacher-score', 'teacher_eval:write'),
    ('M7', 'REST', 'POST',   '/api/v1/tasks/{task_id}/teacher-scores/batch', 'teacher_eval:write'),
    ('M7', 'REST', 'GET',    '/api/v1/tasks/{task_id}/stats', 'teacher_eval:read'),
    ('M7', 'REST', 'GET',    '/api/v1/tasks/{task_id}/leaderboard', 'teacher_eval:read'),
    ('M7', 'REST', 'GET',    '/api/v1/courses/{course_id}/stats', 'teacher_eval:read'),
    ('M7', 'REST', 'GET',    '/api/v1/tasks/{task_id}/export', 'teacher_eval:export'),
    ('M7', 'REST', 'GET',    '/api/v1/student/results', 'student_result:read'),
    ('M7', 'REST', 'GET',    '/api/v1/submissions/{submission_id}/result', 'student_result:read'),

    ('WS', 'WS',   'CONNECT', '/ws/v1/sessions/{session_id}', 'ws:session'),
    ('WS', 'WS',   'CONNECT', '/ws/v1/sessions/{session_id}/perception', 'ws:session')
),
ranked AS (
  SELECT *,
         row_number() OVER (PARTITION BY perm_code ORDER BY module, protocol, method, path_pattern) AS rn
  FROM routes
)
INSERT INTO m1_auth.api_permissions (code, name, module, protocol, method, path_pattern, description)
SELECT
  CASE WHEN rn = 1 THEN perm_code ELSE perm_code || '#' || rn::text END,
  module || ' ' || perm_code,
  module,
  protocol,
  method,
  path_pattern,
  perm_code
FROM ranked
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.account_type_permissions (account_type_id, permission_id)
SELECT at.id, p.id
FROM m1_auth.account_types at
JOIN m1_auth.api_permissions p ON (
  (at.code = 'student' AND p.description IN (
    'public', 'login', 'course:read', 'course:join', 'course:leave',
    'task:read', 'session:start', 'session:read', 'session:submit',
    'session:replay', 'session:events:read', 'ws:session', 'evaluation:read',
    'training:template:read', 'training:session:start', 'training:record:read',
    'training:record:manage', 'student_result:read'
  ))
  OR (at.code = 'teacher' AND (
    p.description IN (
      'public', 'login', 'course:read', 'course:create', 'course:manage',
      'course:members:read', 'course:members:manage',
      'task:read', 'task:create', 'task:publish', 'task:close', 'task:assignments:read',
      'session:read', 'session:replay', 'session:events:read', 'evaluation:read',
      'question_bank:read', 'question_bank:manage',
      'training:template:read', 'training:template:manage',
      'teacher_eval:config', 'teacher_eval:read', 'teacher_eval:write', 'teacher_eval:export'
    )
    OR p.path_pattern = '/api/v1/submissions/{submission_id}/result'
  ))
  OR (at.code = 'admin' AND p.description IN (
    'public', 'login', 'admin:school', 'admin:org', 'admin:teacher_majors',
    'course:read', 'course:manage', 'course:archive',
    'course:members:read', 'course:members:manage',
    'question_bank:read', 'question_bank:manage'
  ))
)
ON CONFLICT DO NOTHING;
