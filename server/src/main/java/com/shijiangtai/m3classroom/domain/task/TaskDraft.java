package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public record TaskDraft(
        TaskMode mode,
        String topic,
        String textbookVersion,
        int durationRecommendedSeconds,
        int durationMaxSeconds,
        RubricWeights rubricWeights,
        ScenarioConfig scenario,
        List<VirtualStudentConfig> virtualStudents,
        QuestionConfig questionConfig) {

    public TaskDraft {
        if (mode == null) {
            throw invalid("mode");
        }
        topic = requireText(topic, 100, "topic");
        textbookVersion = optionalText(textbookVersion, 100, "textbook_version");
        if (durationRecommendedSeconds < 1 || durationRecommendedSeconds > 3600
                || durationMaxSeconds < 1 || durationMaxSeconds > 3600
                || durationRecommendedSeconds > durationMaxSeconds) {
            throw invalid("duration");
        }
        if (rubricWeights == null
                || !rubricWeights.values().keySet()
                        .equals(new LinkedHashSet<>(mode.dimensions()))) {
            throw invalid("rubric_weights");
        }
        if (mode != TaskMode.SCENARIO && scenario != null) {
            throw invalid("scenario");
        }
        List<VirtualStudentConfig> students =
                virtualStudents == null ? List.of() : virtualStudents;
        for (VirtualStudentConfig student : students) {
            if (student == null) {
                throw invalid("virtual_students");
            }
        }
        if (students.size() > 30 || (mode == TaskMode.INTERVIEW && !students.isEmpty())) {
            throw invalid("virtual_students");
        }
        students = List.copyOf(students);
        if (mode == TaskMode.INTERVIEW) {
            if (questionConfig == null) {
                throw invalid("question_config");
            }
            if (questionConfig.totalDurationSeconds() != durationRecommendedSeconds
                    || questionConfig.totalDurationSeconds() != durationMaxSeconds) {
                throw invalid("duration");
            }
        } else if (questionConfig != null) {
            throw invalid("question_config");
        }
        virtualStudents = students;
    }

    private static String requireText(String value, int max, String field) {
        if (value == null) {
            throw invalid(field);
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw invalid(field);
        }
        return trimmed;
    }

    private static String optionalText(String value, int max, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw invalid(field);
        }
        return trimmed;
    }

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "任务配置非法", Map.of("field", field));
    }
}
