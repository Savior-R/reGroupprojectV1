-- Demo-only completed training evaluation for student.a01's retained training record.
-- Loaded only from classpath:db/demo; never part of test / prod locations.
-- Contract: 06 §9.2 report DTO, 08 §10.6 self-only training report.
--
-- The M4 reconciler may have created a failed placeholder evaluation for this
-- training record before the demo seed was upgraded; replace it deterministically.

DELETE FROM m4_eval.evaluations
WHERE source_type = 'training_record'
  AND source_id = 'a0000000-0000-0000-0000-000000000011';

INSERT INTO m4_eval.evaluations (
    id, source_type, source_id, submission_id, training_record_id, session_id, task_id,
    course_id, student_account_id, mode, status, total_score, weights,
    task_config_version, training_config_version, model_version, prompt_version,
    rubric_version, attempt_count, warnings, partial_reasons, started_at, completed_at)
VALUES (
    '80000000-0000-0000-0000-000000000011', 'training_record',
    'a0000000-0000-0000-0000-000000000011', NULL,
    'a0000000-0000-0000-0000-000000000011',
    '70000000-0000-0000-0000-000000000006',
    '60000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000101',
    '20000000-0000-0000-0000-000000000101',
    'free', 'completed', 84.50,
    '{"content": 30, "expression": 20, "interaction": 20, "board": 15, "time": 15}'::jsonb,
    NULL, 1, 'mock-v1', 'p1', 'r1', 1, '[]'::jsonb, '[]'::jsonb,
    '2026-11-12 10:00:00+00', '2026-11-12 10:05:00+00')
ON CONFLICT DO NOTHING;

INSERT INTO m4_eval.dimension_scores
    (evaluation_id, dimension, score, sub_scores, sub_confidences, confidence)
VALUES
    ('80000000-0000-0000-0000-000000000011', 'content', 86.00,
     '{"content": 86}'::jsonb, '{"content": 0.9}'::jsonb, 0.90),
    ('80000000-0000-0000-0000-000000000011', 'expression', 82.00,
     '{"expression": 82}'::jsonb, '{"expression": 0.9}'::jsonb, 0.90),
    ('80000000-0000-0000-0000-000000000011', 'interaction', 80.00,
     '{"interaction": 80}'::jsonb, '{"interaction": 0.9}'::jsonb, 0.90),
    ('80000000-0000-0000-0000-000000000011', 'board', 88.00,
     '{"board": 88}'::jsonb, '{"board": 0.9}'::jsonb, 0.90),
    ('80000000-0000-0000-0000-000000000011', 'time', 87.00,
     '{"time": 87}'::jsonb, '{"time": 0.9}'::jsonb, 0.90)
ON CONFLICT (evaluation_id, dimension) DO NOTHING;

INSERT INTO m4_eval.issues
    (id, evaluation_id, dimension, start_ms, end_ms, severity, evidence, suggestion,
     is_suggestion, suggestion_rank, sort_order)
VALUES
    ('b0000000-0000-0000-0000-000000000001',
     '80000000-0000-0000-0000-000000000011', 'content', 12000, 18000, 'high',
     '提问后等待时间不足', '提问后至少等待 3 秒，再点名或补充', true, 1, 1),
    ('b0000000-0000-0000-0000-000000000002',
     '80000000-0000-0000-0000-000000000011', 'interaction', 42000, 50000, 'medium',
     '只叫了前排学生', '扩大提问覆盖面，兼顾后排学生', true, 2, 2),
    ('b0000000-0000-0000-0000-000000000003',
     '80000000-0000-0000-0000-000000000011', 'expression', 61000, 72000, 'low',
     '语速偏快', '在重点段落放慢语速', false, NULL, 3)
ON CONFLICT (id) DO NOTHING;

UPDATE m6_training.training_records
SET status = 'evaluated', updated_at = '2026-11-12 10:05:00+00'
WHERE id = 'a0000000-0000-0000-0000-000000000011';
