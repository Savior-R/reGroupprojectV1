package com.shijiangtai.m7teacher.application.view;

import java.util.UUID;

public record BatchRowView(
        UUID submissionId,
        boolean ok,
        TeacherScoreRow teacherScore,
        FinalScoreRow finalScore,
        String errorCode,
        String message) {
}
