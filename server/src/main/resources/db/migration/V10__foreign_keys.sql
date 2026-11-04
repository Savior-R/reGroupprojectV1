-- All cross-schema foreign keys, applied after every table exists.

-- M1
ALTER TABLE m1_auth.accounts
  ADD CONSTRAINT fk_accounts_type FOREIGN KEY (account_type_id) REFERENCES m1_auth.account_types(id);
ALTER TABLE m1_auth.accounts
  ADD CONSTRAINT fk_accounts_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE RESTRICT;
ALTER TABLE m1_auth.school_admins
  ADD CONSTRAINT fk_school_admins_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.school_admins
  ADD CONSTRAINT fk_school_admins_account FOREIGN KEY (account_id) REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.majors
  ADD CONSTRAINT fk_majors_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.classes
  ADD CONSTRAINT fk_classes_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.classes
  ADD CONSTRAINT fk_classes_major FOREIGN KEY (major_id) REFERENCES m1_auth.majors(id) ON DELETE RESTRICT;
ALTER TABLE m1_auth.student_profiles
  ADD CONSTRAINT fk_student_profiles_account FOREIGN KEY (account_id) REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.student_profiles
  ADD CONSTRAINT fk_student_profiles_class FOREIGN KEY (class_id) REFERENCES m1_auth.classes(id) ON DELETE RESTRICT;
ALTER TABLE m1_auth.teacher_profiles
  ADD CONSTRAINT fk_teacher_profiles_account FOREIGN KEY (account_id) REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.teacher_majors
  ADD CONSTRAINT fk_teacher_majors_account FOREIGN KEY (account_id) REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.teacher_majors
  ADD CONSTRAINT fk_teacher_majors_major FOREIGN KEY (major_id) REFERENCES m1_auth.majors(id) ON DELETE RESTRICT;
ALTER TABLE m1_auth.account_type_permissions
  ADD CONSTRAINT fk_atp_type FOREIGN KEY (account_type_id) REFERENCES m1_auth.account_types(id) ON DELETE CASCADE;
ALTER TABLE m1_auth.account_type_permissions
  ADD CONSTRAINT fk_atp_permission FOREIGN KEY (permission_id) REFERENCES m1_auth.api_permissions(id) ON DELETE CASCADE;

-- M2
ALTER TABLE m2_course.courses
  ADD CONSTRAINT fk_courses_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE RESTRICT;
ALTER TABLE m2_course.courses
  ADD CONSTRAINT fk_courses_creator FOREIGN KEY (created_by) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m2_course.course_majors
  ADD CONSTRAINT fk_course_majors_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE CASCADE;
ALTER TABLE m2_course.course_majors
  ADD CONSTRAINT fk_course_majors_major FOREIGN KEY (major_id) REFERENCES m1_auth.majors(id) ON DELETE RESTRICT;
ALTER TABLE m2_course.course_teachers
  ADD CONSTRAINT fk_course_teachers_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE CASCADE;
ALTER TABLE m2_course.course_teachers
  ADD CONSTRAINT fk_course_teachers_account FOREIGN KEY (account_id) REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;
ALTER TABLE m2_course.course_students
  ADD CONSTRAINT fk_course_students_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE CASCADE;
ALTER TABLE m2_course.course_students
  ADD CONSTRAINT fk_course_students_account FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;

-- M3
ALTER TABLE m3_classroom.tasks
  ADD CONSTRAINT fk_tasks_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE RESTRICT;
ALTER TABLE m3_classroom.tasks
  ADD CONSTRAINT fk_tasks_creator FOREIGN KEY (created_by) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m3_classroom.task_assignments
  ADD CONSTRAINT fk_task_assignments_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE CASCADE;
ALTER TABLE m3_classroom.task_assignments
  ADD CONSTRAINT fk_task_assignments_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m3_classroom.task_assignments
  ADD CONSTRAINT fk_task_assignments_class FOREIGN KEY (class_id_snapshot) REFERENCES m1_auth.classes(id) ON DELETE SET NULL;
ALTER TABLE m3_classroom.virtual_students
  ADD CONSTRAINT fk_virtual_students_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE CASCADE;
ALTER TABLE m3_classroom.sessions
  ADD CONSTRAINT fk_sessions_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE SET NULL;
ALTER TABLE m3_classroom.sessions
  ADD CONSTRAINT fk_sessions_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE SET NULL;
ALTER TABLE m3_classroom.sessions
  ADD CONSTRAINT fk_sessions_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m3_classroom.submissions
  ADD CONSTRAINT fk_submissions_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE RESTRICT;
ALTER TABLE m3_classroom.submissions
  ADD CONSTRAINT fk_submissions_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m3_classroom.submissions
  ADD CONSTRAINT fk_submissions_session FOREIGN KEY (session_id) REFERENCES m3_classroom.sessions(id) ON DELETE RESTRICT;
ALTER TABLE m3_classroom.submissions
  ADD CONSTRAINT fk_submissions_class FOREIGN KEY (class_id_snapshot) REFERENCES m1_auth.classes(id) ON DELETE SET NULL;
ALTER TABLE m3_classroom.recordings
  ADD CONSTRAINT fk_recordings_session FOREIGN KEY (session_id) REFERENCES m3_classroom.sessions(id) ON DELETE CASCADE;
ALTER TABLE m3_classroom.recording_streams
  ADD CONSTRAINT fk_recording_streams_recording FOREIGN KEY (recording_id) REFERENCES m3_classroom.recordings(id) ON DELETE CASCADE;
ALTER TABLE m3_classroom.session_texts
  ADD CONSTRAINT fk_session_texts_session FOREIGN KEY (session_id) REFERENCES m3_classroom.sessions(id) ON DELETE CASCADE;
ALTER TABLE m3_classroom.frames
  ADD CONSTRAINT fk_frames_session FOREIGN KEY (session_id) REFERENCES m3_classroom.sessions(id) ON DELETE CASCADE;
ALTER TABLE m3_classroom.events
  ADD CONSTRAINT fk_events_session FOREIGN KEY (session_id) REFERENCES m3_classroom.sessions(id) ON DELETE CASCADE;

-- M5
ALTER TABLE m5_question.libraries
  ADD CONSTRAINT fk_libraries_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE CASCADE;
ALTER TABLE m5_question.categories
  ADD CONSTRAINT fk_categories_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE CASCADE;
ALTER TABLE m5_question.categories
  ADD CONSTRAINT fk_categories_creator FOREIGN KEY (created_by) REFERENCES m1_auth.accounts(id) ON DELETE SET NULL;
ALTER TABLE m5_question.categories
  ADD CONSTRAINT fk_categories_updater FOREIGN KEY (updated_by) REFERENCES m1_auth.accounts(id) ON DELETE SET NULL;
ALTER TABLE m5_question.questions
  ADD CONSTRAINT fk_questions_school FOREIGN KEY (school_id) REFERENCES m1_auth.schools(id) ON DELETE CASCADE;
ALTER TABLE m5_question.questions
  ADD CONSTRAINT fk_questions_category FOREIGN KEY (category_id) REFERENCES m5_question.categories(id) ON DELETE RESTRICT;
ALTER TABLE m5_question.questions
  ADD CONSTRAINT fk_questions_creator FOREIGN KEY (created_by) REFERENCES m1_auth.accounts(id) ON DELETE SET NULL;
ALTER TABLE m5_question.questions
  ADD CONSTRAINT fk_questions_updater FOREIGN KEY (updated_by) REFERENCES m1_auth.accounts(id) ON DELETE SET NULL;

-- M6
ALTER TABLE m6_training.training_templates
  ADD CONSTRAINT fk_training_templates_task FOREIGN KEY (source_task_id) REFERENCES m3_classroom.tasks(id) ON DELETE RESTRICT;
ALTER TABLE m6_training.training_templates
  ADD CONSTRAINT fk_training_templates_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE RESTRICT;
ALTER TABLE m6_training.training_templates
  ADD CONSTRAINT fk_training_templates_creator FOREIGN KEY (created_by) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m6_training.training_session_meta
  ADD CONSTRAINT fk_training_meta_session FOREIGN KEY (session_id) REFERENCES m3_classroom.sessions(id) ON DELETE CASCADE;
ALTER TABLE m6_training.training_session_meta
  ADD CONSTRAINT fk_training_meta_template FOREIGN KEY (template_id) REFERENCES m6_training.training_templates(id) ON DELETE SET NULL;
ALTER TABLE m6_training.training_records
  ADD CONSTRAINT fk_training_records_session FOREIGN KEY (training_session_id) REFERENCES m3_classroom.sessions(id) ON DELETE CASCADE;
ALTER TABLE m6_training.training_records
  ADD CONSTRAINT fk_training_records_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m6_training.training_records
  ADD CONSTRAINT fk_training_records_template FOREIGN KEY (template_id) REFERENCES m6_training.training_templates(id) ON DELETE SET NULL;
ALTER TABLE m6_training.training_materials
  ADD CONSTRAINT fk_training_materials_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE CASCADE;

-- M4
ALTER TABLE m4_eval.evaluations
  ADD CONSTRAINT fk_evaluations_submission FOREIGN KEY (submission_id) REFERENCES m3_classroom.submissions(id) ON DELETE CASCADE;
ALTER TABLE m4_eval.evaluations
  ADD CONSTRAINT fk_evaluations_training_record FOREIGN KEY (training_record_id) REFERENCES m6_training.training_records(id) ON DELETE CASCADE;
ALTER TABLE m4_eval.evaluations
  ADD CONSTRAINT fk_evaluations_session FOREIGN KEY (session_id) REFERENCES m3_classroom.sessions(id) ON DELETE RESTRICT;
ALTER TABLE m4_eval.evaluations
  ADD CONSTRAINT fk_evaluations_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE SET NULL;
ALTER TABLE m4_eval.evaluations
  ADD CONSTRAINT fk_evaluations_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE SET NULL;
ALTER TABLE m4_eval.evaluations
  ADD CONSTRAINT fk_evaluations_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m4_eval.dimension_scores
  ADD CONSTRAINT fk_dimension_scores_evaluation FOREIGN KEY (evaluation_id) REFERENCES m4_eval.evaluations(id) ON DELETE CASCADE;
ALTER TABLE m4_eval.issues
  ADD CONSTRAINT fk_issues_evaluation FOREIGN KEY (evaluation_id) REFERENCES m4_eval.evaluations(id) ON DELETE CASCADE;
ALTER TABLE m4_eval.input_snapshots
  ADD CONSTRAINT fk_input_snapshots_evaluation FOREIGN KEY (evaluation_id) REFERENCES m4_eval.evaluations(id) ON DELETE CASCADE;
ALTER TABLE m4_eval.evaluation_jobs
  ADD CONSTRAINT fk_evaluation_jobs_evaluation FOREIGN KEY (evaluation_id) REFERENCES m4_eval.evaluations(id) ON DELETE CASCADE;

-- M7
ALTER TABLE m7_teacher.task_grading_configs
  ADD CONSTRAINT fk_grading_configs_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE CASCADE;
ALTER TABLE m7_teacher.task_grading_configs
  ADD CONSTRAINT fk_grading_configs_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE CASCADE;
ALTER TABLE m7_teacher.task_grading_configs
  ADD CONSTRAINT fk_grading_configs_updater FOREIGN KEY (updated_by) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.teacher_scores
  ADD CONSTRAINT fk_teacher_scores_submission FOREIGN KEY (submission_id) REFERENCES m3_classroom.submissions(id) ON DELETE CASCADE;
ALTER TABLE m7_teacher.teacher_scores
  ADD CONSTRAINT fk_teacher_scores_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.teacher_scores
  ADD CONSTRAINT fk_teacher_scores_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.teacher_scores
  ADD CONSTRAINT fk_teacher_scores_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.teacher_scores
  ADD CONSTRAINT fk_teacher_scores_class FOREIGN KEY (class_id_snapshot) REFERENCES m1_auth.classes(id) ON DELETE SET NULL;
ALTER TABLE m7_teacher.teacher_scores
  ADD CONSTRAINT fk_teacher_scores_grader FOREIGN KEY (graded_by) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.final_scores
  ADD CONSTRAINT fk_final_scores_submission FOREIGN KEY (submission_id) REFERENCES m3_classroom.submissions(id) ON DELETE CASCADE;
ALTER TABLE m7_teacher.final_scores
  ADD CONSTRAINT fk_final_scores_task FOREIGN KEY (task_id) REFERENCES m3_classroom.tasks(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.final_scores
  ADD CONSTRAINT fk_final_scores_course FOREIGN KEY (course_id) REFERENCES m2_course.courses(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.final_scores
  ADD CONSTRAINT fk_final_scores_student FOREIGN KEY (student_account_id) REFERENCES m1_auth.accounts(id) ON DELETE RESTRICT;
ALTER TABLE m7_teacher.final_scores
  ADD CONSTRAINT fk_final_scores_class FOREIGN KEY (class_id_snapshot) REFERENCES m1_auth.classes(id) ON DELETE SET NULL;
ALTER TABLE m7_teacher.final_scores
  ADD CONSTRAINT fk_final_scores_ai FOREIGN KEY (ai_evaluation_id) REFERENCES m4_eval.evaluations(id) ON DELETE SET NULL;
ALTER TABLE m7_teacher.final_scores
  ADD CONSTRAINT fk_final_scores_teacher FOREIGN KEY (teacher_score_id) REFERENCES m7_teacher.teacher_scores(id) ON DELETE SET NULL;
