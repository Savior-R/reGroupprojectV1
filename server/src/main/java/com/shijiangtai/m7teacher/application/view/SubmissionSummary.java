package com.shijiangtai.m7teacher.application.view;

/** Grading roster summary (07 §11.2). */
public record SubmissionSummary(
        int assigned,
        int submitted,
        int completed,
        int pendingAi,
        int pendingTeacher,
        int aiPartial,
        int aiFailed,
        int notSubmitted,
        int inProgress) {
}
