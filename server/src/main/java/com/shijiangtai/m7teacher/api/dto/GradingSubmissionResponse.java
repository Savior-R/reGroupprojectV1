package com.shijiangtai.m7teacher.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shijiangtai.m7teacher.application.view.AiScoreView;
import com.shijiangtai.m7teacher.application.view.FinalScoreRow;
import com.shijiangtai.m7teacher.application.view.GradingSubmissionRow;
import com.shijiangtai.m7teacher.application.view.GradingSubmissionView;
import com.shijiangtai.m7teacher.application.view.SubmissionSummary;
import com.shijiangtai.m7teacher.application.view.TeacherScoreRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire DTO for the roster; {@code finalScore} is exposed as {@code final}. */
public record GradingSubmissionResponse(
        UUID taskId,
        UUID courseId,
        String mode,
        String scoringMode,
        Map<String, Integer> rubricWeights,
        SubmissionSummary summary,
        List<Item> items,
        long total,
        int page,
        int size) {

    public static GradingSubmissionResponse from(GradingSubmissionView view) {
        return new GradingSubmissionResponse(
                view.taskId(), view.courseId(), view.mode(), view.scoringMode(),
                view.rubricWeights(), view.summary(),
                view.items().stream().map(Item::from).toList(),
                view.total(), view.page(), view.size());
    }

    public record Item(
            UUID submissionId,
            UUID studentAccountId,
            String studentName,
            UUID classId,
            String className,
            UUID classIdSnapshot,
            String studentStatus,
            String sessionStatus,
            Instant submittedAt,
            AiScoreView ai,
            TeacherScoreRow teacherScore,
            @JsonProperty("final") FinalScoreRow finalScore) {

        static Item from(GradingSubmissionRow row) {
            return new Item(row.submissionId(), row.studentAccountId(), row.studentName(),
                    row.classId(), row.className(), row.classIdSnapshot(), row.studentStatus(),
                    row.sessionStatus(), row.submittedAt(), row.ai(), row.teacherScore(),
                    row.finalScore());
        }
    }
}
