package com.shijiangtai.m7teacher.application.view;

/** Result of one teacher-score save; final score may still be pending. */
public record TeacherScoreSaveView(TeacherScoreRow teacherScore, FinalScoreRow finalScore) {
}
