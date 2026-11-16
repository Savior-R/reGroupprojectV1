package com.shijiangtai.m7teacher.application.view;

import java.time.Instant;
import java.util.UUID;

/** One roster row; {@code finalScore} is serialized as {@code final} by the API DTO. */
public record GradingSubmissionRow(
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
        FinalScoreRow finalScore) {
}
