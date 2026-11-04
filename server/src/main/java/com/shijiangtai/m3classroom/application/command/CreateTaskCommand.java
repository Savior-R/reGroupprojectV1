package com.shijiangtai.m3classroom.application.command;

import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.CourseId;
import java.util.List;
import java.util.Map;

public record CreateTaskCommand(
        CourseId courseId,
        TaskMode mode,
        String topic,
        String textbookVersion,
        Integer durationRecommendedSeconds,
        Integer durationMaxSeconds,
        Map<String, Integer> rubricWeights,
        ScenarioConfig scenario,
        List<VirtualStudentSpec> virtualStudents,
        QuestionConfig questionConfig) {
}
