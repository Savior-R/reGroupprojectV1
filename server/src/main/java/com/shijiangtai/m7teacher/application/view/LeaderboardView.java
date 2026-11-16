package com.shijiangtai.m7teacher.application.view;

import java.util.List;
import java.util.UUID;

public record LeaderboardView(
        UUID taskId,
        UUID classId,
        List<LeaderboardItem> items,
        long total,
        int page,
        int size) {
}
