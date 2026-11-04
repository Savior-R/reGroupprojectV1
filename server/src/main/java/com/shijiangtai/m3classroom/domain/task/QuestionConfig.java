package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record QuestionConfig(List<QuestionId> questionIds, int totalDurationSeconds) {

    public QuestionConfig {
        if (questionIds == null) {
            throw invalid("question_config.question_ids");
        }
        for (QuestionId questionId : questionIds) {
            if (questionId == null) {
                throw invalid("question_config.question_ids");
            }
        }
        List<QuestionId> copy = List.copyOf(questionIds);
        if (copy.size() > 10 || new HashSet<>(copy).size() != copy.size()) {
            throw invalid("question_config.question_ids");
        }
        if (totalDurationSeconds < 1 || totalDurationSeconds > 3600) {
            throw invalid("question_config.total_duration_seconds");
        }
        questionIds = copy;
    }

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "面试题目配置非法", Map.of("field", field));
    }
}
