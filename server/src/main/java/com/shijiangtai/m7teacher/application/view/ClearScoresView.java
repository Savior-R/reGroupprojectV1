package com.shijiangtai.m7teacher.application.view;

/** Result of clearing all teacher scores for one task (07 §4.3). */
public record ClearScoresView(int deletedCount, boolean unlocked, int recomputed) {
}
