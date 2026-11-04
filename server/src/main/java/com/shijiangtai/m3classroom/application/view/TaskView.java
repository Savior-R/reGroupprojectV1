package com.shijiangtai.m3classroom.application.view;

import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TaskView(
        UUID taskId,
        UUID courseId,
        String mode,
        String topic,
        String textbookVersion,
        int durationRecommendedSeconds,
        int durationMaxSeconds,
        Map<String, Integer> rubricWeights,
        ReferenceMaterialView referenceMaterial,
        ScenarioConfig scenario,
        List<VirtualStudentView> virtualStudents,
        QuestionConfigView questionConfig,
        List<PublishedQuestionView> publishedQuestions,
        String status,
        int configVersion,
        Instant publishedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt) {
}
