-- Business indexes and partial unique indexes.

-- M1
CREATE UNIQUE INDEX uq_school_admins_account
  ON m1_auth.school_admins (account_id) WHERE status = 'active';
CREATE UNIQUE INDEX uq_school_admins_school
  ON m1_auth.school_admins (school_id) WHERE status = 'active';
CREATE UNIQUE INDEX uq_teacher_majors_active
  ON m1_auth.teacher_majors (account_id, major_id) WHERE status = 'active';
CREATE INDEX idx_accounts_school_type ON m1_auth.accounts (school_id, account_type_id);
CREATE INDEX idx_majors_school_status ON m1_auth.majors (school_id, status);
CREATE INDEX idx_classes_school_major_status ON m1_auth.classes (school_id, major_id, status);
CREATE INDEX idx_teacher_majors_account ON m1_auth.teacher_majors (account_id);
CREATE INDEX idx_teacher_majors_major ON m1_auth.teacher_majors (major_id);

-- M2
CREATE UNIQUE INDEX uq_course_teachers_active
  ON m2_course.course_teachers (course_id, account_id) WHERE status = 'active';
CREATE UNIQUE INDEX uq_course_students_active
  ON m2_course.course_students (course_id, student_account_id) WHERE status = 'active';
CREATE INDEX idx_courses_school_status ON m2_course.courses (school_id, status);
CREATE INDEX idx_courses_created_by ON m2_course.courses (created_by);
CREATE INDEX idx_course_teachers_course ON m2_course.course_teachers (course_id, status);
CREATE INDEX idx_course_students_course ON m2_course.course_students (course_id, status);

-- M3
CREATE UNIQUE INDEX uq_sessions_task_active
  ON m3_classroom.sessions (task_id, student_account_id)
  WHERE session_type = 'task' AND status IN ('preparing', 'in_progress');
CREATE UNIQUE INDEX uq_sessions_training_active
  ON m3_classroom.sessions (student_account_id)
  WHERE session_type = 'training' AND status IN ('preparing', 'in_progress');
CREATE INDEX idx_tasks_course_status ON m3_classroom.tasks (course_id, status);
CREATE INDEX idx_tasks_created_by ON m3_classroom.tasks (created_by);
CREATE INDEX idx_task_assignments_task ON m3_classroom.task_assignments (task_id);
CREATE INDEX idx_task_assignments_student ON m3_classroom.task_assignments (student_account_id);
CREATE INDEX idx_task_assignments_class ON m3_classroom.task_assignments (task_id, class_id_snapshot);
CREATE INDEX idx_virtual_students_task ON m3_classroom.virtual_students (task_id);
CREATE INDEX idx_sessions_student_status ON m3_classroom.sessions (student_account_id, status);
CREATE INDEX idx_sessions_task ON m3_classroom.sessions (task_id);
CREATE INDEX idx_sessions_course ON m3_classroom.sessions (course_id);
CREATE INDEX idx_sessions_type ON m3_classroom.sessions (session_type);
CREATE INDEX idx_submissions_task_student ON m3_classroom.submissions (task_id, student_account_id);
CREATE INDEX idx_recordings_status ON m3_classroom.recordings (status);
CREATE INDEX idx_recording_streams_recording ON m3_classroom.recording_streams (recording_id);
CREATE INDEX idx_recording_streams_status ON m3_classroom.recording_streams (status);
CREATE INDEX idx_session_texts_at ON m3_classroom.session_texts (session_id, at_ms);
CREATE INDEX idx_session_texts_speaker ON m3_classroom.session_texts (session_id, speaker_type);
CREATE INDEX idx_session_texts_kind ON m3_classroom.session_texts (session_id, kind);
CREATE INDEX idx_frames_time ON m3_classroom.frames (session_id, timestamp_ms);
CREATE INDEX idx_frames_type ON m3_classroom.frames (session_id, frame_type);
CREATE INDEX idx_frames_status ON m3_classroom.frames (session_id, status);
CREATE INDEX idx_events_time ON m3_classroom.events (session_id, timestamp_ms);
CREATE INDEX idx_events_type ON m3_classroom.events (session_id, type);

-- M5
CREATE INDEX idx_categories_school_status_sort ON m5_question.categories (school_id, status, sort_order);
CREATE INDEX idx_questions_school_category_status ON m5_question.questions (school_id, category_id, status);
CREATE INDEX idx_questions_school_status ON m5_question.questions (school_id, status);

-- M6
CREATE UNIQUE INDEX uq_training_templates_published
  ON m6_training.training_templates (source_task_id, course_id, training_level)
  WHERE status = 'published';
CREATE INDEX idx_training_templates_course ON m6_training.training_templates (course_id, status);
CREATE INDEX idx_training_templates_task ON m6_training.training_templates (source_task_id);
CREATE INDEX idx_training_records_student ON m6_training.training_records (student_account_id, template_id, expires_at);
CREATE INDEX idx_training_records_expiry ON m6_training.training_records (expires_at, status);
CREATE INDEX idx_training_materials_student ON m6_training.training_materials (student_account_id, status);
CREATE INDEX idx_training_materials_expiry ON m6_training.training_materials (expires_at, status);

-- M4
CREATE INDEX idx_evaluations_student_status ON m4_eval.evaluations (student_account_id, status);
CREATE INDEX idx_evaluations_submission ON m4_eval.evaluations (submission_id);
CREATE INDEX idx_evaluations_training ON m4_eval.evaluations (training_record_id);
CREATE INDEX idx_issues_dimension ON m4_eval.issues (evaluation_id, dimension);
CREATE INDEX idx_issues_time ON m4_eval.issues (evaluation_id, start_ms);
CREATE INDEX idx_issues_suggestion ON m4_eval.issues (evaluation_id, is_suggestion, suggestion_rank);
CREATE INDEX idx_evaluation_jobs_retry ON m4_eval.evaluation_jobs (status, next_retry_at);

-- M7
CREATE INDEX idx_teacher_scores_task_student ON m7_teacher.teacher_scores (task_id, student_account_id);
CREATE INDEX idx_teacher_scores_course ON m7_teacher.teacher_scores (course_id);
CREATE INDEX idx_final_scores_task_status ON m7_teacher.final_scores (task_id, status);
CREATE INDEX idx_final_scores_course_status ON m7_teacher.final_scores (course_id, status);
CREATE INDEX idx_final_scores_class_status ON m7_teacher.final_scores (class_id_snapshot, status);
