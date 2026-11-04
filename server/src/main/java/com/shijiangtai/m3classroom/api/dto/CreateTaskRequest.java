package com.shijiangtai.m3classroom.api.dto;

import java.util.List;
import java.util.Map;

public record CreateTaskRequest(
        String mode,
        String topic,
        String textbookVersion,
        Integer durationRecommendedSeconds,
        Integer durationMaxSeconds,
        Map<String, Integer> rubricWeights,
        ScenarioRequest scenario,
        List<VirtualStudentRequest> virtualStudents,
        QuestionConfigRequest questionConfig) {
}
