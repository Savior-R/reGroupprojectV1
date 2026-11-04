package com.shijiangtai.m3classroom.application.command;

import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import java.util.List;
import java.util.Map;

public record UpdateTaskCommand(
        Patch<TaskMode> mode,
        Patch<String> topic,
        Patch<String> textbookVersion,
        Patch<Integer> durationRecommendedSeconds,
        Patch<Integer> durationMaxSeconds,
        Patch<Map<String, Integer>> rubricWeights,
        Patch<ScenarioConfig> scenario,
        Patch<List<VirtualStudentSpec>> virtualStudents,
        Patch<QuestionConfig> questionConfig) {
}
