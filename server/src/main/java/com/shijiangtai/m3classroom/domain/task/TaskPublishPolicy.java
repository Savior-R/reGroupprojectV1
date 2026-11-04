package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TaskPublishPolicy {

    public void validateForPublish(
            Task task,
            CourseInfo course,
            List<PublishQuestion> questions,
            List<CourseStudentInfo> activeStudents,
            Instant now) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(now, "now");
        List<PublishQuestion> questionList = questions == null ? List.of() : questions;
        List<CourseStudentInfo> studentList =
                activeStudents == null ? List.of() : activeStudents;
        TaskDraft draft = task.draft();
        TaskMode mode = draft.mode();

        if (task.status() == TaskStatus.PUBLISHED) {
            throw new DomainException(ErrorCode.TASK_ALREADY_PUBLISHED,
                    "任务已发布", Map.of("task_id", task.id().value()));
        }
        if (task.status() == TaskStatus.CLOSED) {
            throw new DomainException(ErrorCode.TASK_NOT_DRAFT,
                    "任务已关闭", Map.of("task_id", task.id().value()));
        }
        if (!course.active()) {
            throw new DomainException(ErrorCode.COURSE_ARCHIVED,
                    "课程已归档", Map.of("course_id", course.id()));
        }
        if (course.endAt() != null && !course.endAt().isAfter(now)) {
            throw new DomainException(ErrorCode.COURSE_ENDED,
                    "课程已结束", Map.of("course_id", course.id()));
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        if (draft.topic() == null || draft.topic().isBlank()) {
            addField(fields, "topic", "课题不能为空");
        }
        if (mode.requiresMaterial()
                && (draft.textbookVersion() == null || draft.textbookVersion().isBlank())) {
            addField(fields, "textbook_version", "教材版本不能为空");
        }
        if (draft.durationRecommendedSeconds() < 1
                || draft.durationRecommendedSeconds() > 3600
                || draft.durationMaxSeconds() < 1
                || draft.durationMaxSeconds() > 3600
                || draft.durationRecommendedSeconds() > draft.durationMaxSeconds()) {
            addField(fields, "duration", "时长非法");
        }
        if (mode.requiresVirtualStudents()) {
            int count = draft.virtualStudents().size();
            if (count < 1 || count > 30) {
                addField(fields, "virtual_students", "虚拟学生数量必须为 1-30");
            }
        }
        if (mode == TaskMode.INTERVIEW) {
            QuestionConfig questionConfig = draft.questionConfig();
            if (questionConfig != null
                    && (questionConfig.totalDurationSeconds()
                            != draft.durationRecommendedSeconds()
                    || questionConfig.totalDurationSeconds() != draft.durationMaxSeconds())) {
                addField(fields, "duration", "面试时长必须与题目总时长一致");
            }
        }

        if (draft.rubricWeights().sum() != 100) {
            throw new DomainException(ErrorCode.WEIGHT_SUM_INVALID,
                    "权重和必须为 100", Map.of("sum", draft.rubricWeights().sum()));
        }

        if (mode.requiresMaterial()) {
            ReferenceMaterial material = task.referenceMaterial();
            if (material == null) {
                throw new DomainException(ErrorCode.REFERENCE_MATERIAL_MISSING,
                        "参考材料缺失", Map.of("field", "reference_material"));
            }
            if (material.parsedText() == null || material.parsedText().isBlank()) {
                throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED,
                        "参考材料解析失败", Map.of("field", "reference_material"));
            }
        }

        if (mode == TaskMode.INTERVIEW) {
            validateInterview(draft.questionConfig(), questionList, course);
        }

        if (studentList.isEmpty()) {
            throw new DomainException(ErrorCode.TASK_NO_STUDENTS, "课程无在课学生");
        }
        if (!fields.isEmpty()) {
            throw DomainException.fields(
                    ErrorCode.TASK_CONFIG_INVALID, "任务配置不完整", fields);
        }
    }

    private void validateInterview(
            QuestionConfig questionConfig,
            List<PublishQuestion> questions,
            CourseInfo course) {
        if (questionConfig == null || questionConfig.questionIds().isEmpty()) {
            throw new DomainException(ErrorCode.QUESTION_COUNT_INVALID,
                    "面试题目数量必须为 1-10", Map.of("count", 0));
        }
        List<QuestionId> questionIds = questionConfig.questionIds();
        if (questionIds.size() > 10) {
            throw new DomainException(ErrorCode.QUESTION_COUNT_INVALID,
                    "面试题目数量必须为 1-10", Map.of("count", questionIds.size()));
        }
        if (new HashSet<>(questionIds).size() != questionIds.size()) {
            throw new DomainException(ErrorCode.QUESTION_DUPLICATED,
                    "面试题目重复", Map.of("question_ids", questionIds.stream()
                            .map(QuestionId::value).toList()));
        }
        if (questions.size() != questionIds.size()) {
            throw new DomainException(ErrorCode.QUESTION_DISABLED,
                    "面试题目不可用", Map.of("question_ids", questionIds.stream()
                            .map(QuestionId::value).toList()));
        }
        for (PublishQuestion question : questions) {
            if (!question.enabled()) {
                throw new DomainException(ErrorCode.QUESTION_DISABLED,
                        "面试题目已停用", Map.of("question_id", question.questionId()));
            }
            if (question.answerPoints() == null || question.answerPoints().isBlank()) {
                throw new DomainException(ErrorCode.ANSWER_POINTS_MISSING,
                        "面试题目缺少答案要点", Map.of("question_id", question.questionId()));
            }
            if (question.schoolId() == null || !question.schoolId().equals(course.schoolId())) {
                throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS,
                        "跨校题目不可用", Map.of("question_id", question.questionId()));
            }
        }
    }

    private static void addField(
            List<Map<String, Object>> fields, String field, String message) {
        fields.add(Map.of("field", field, "message", message));
    }
}
