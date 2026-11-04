-- Dev-only upstream rows for the fixed DevDataConfig IDs.
-- Loaded only by the docker profile (db/dev location), never by test/demo/prod.

INSERT INTO m1_auth.schools (id, name) VALUES
  ('00000000-0000-0000-0000-000000000001', '开发学校')
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.accounts (id, username, password_hash, account_type_id, school_id)
SELECT '00000000-0000-0000-0000-000000000010', 'dev.teacher', 'x', id,
       '00000000-0000-0000-0000-000000000001'
FROM m1_auth.account_types WHERE code = 'teacher'
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.accounts (id, username, password_hash, account_type_id, school_id)
SELECT '00000000-0000-0000-0000-000000000011', 'dev.other.teacher', 'x', id,
       '00000000-0000-0000-0000-000000000001'
FROM m1_auth.account_types WHERE code = 'teacher'
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.accounts (id, username, password_hash, account_type_id, school_id)
SELECT '00000000-0000-0000-0000-000000000020', 'dev.student', 'x', id,
       '00000000-0000-0000-0000-000000000001'
FROM m1_auth.account_types WHERE code = 'student'
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.accounts (id, username, password_hash, account_type_id, school_id)
SELECT '00000000-0000-0000-0000-000000000021', 'dev.student2', 'x', id,
       '00000000-0000-0000-0000-000000000001'
FROM m1_auth.account_types WHERE code = 'student'
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.majors (id, school_id, name) VALUES
  ('00000000-0000-0000-0000-000000000300', '00000000-0000-0000-0000-000000000001',
   '汉语言文学')
ON CONFLICT DO NOTHING;

INSERT INTO m1_auth.classes (id, school_id, major_id, grade, name) VALUES
  ('00000000-0000-0000-0000-000000000200', '00000000-0000-0000-0000-000000000001',
   '00000000-0000-0000-0000-000000000300', '2024', '汉语言2401')
ON CONFLICT DO NOTHING;

INSERT INTO m2_course.courses (id, school_id, name, created_by) VALUES
  ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000001',
   '语文教学论', '00000000-0000-0000-0000-000000000010')
ON CONFLICT DO NOTHING;
