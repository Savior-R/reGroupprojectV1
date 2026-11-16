package com.shijiangtai.m7teacher.application.view;

import java.util.List;
import java.util.UUID;

public record CourseStatsView(
        UUID courseId, UUID classId, CourseStatsSummary summary, List<CourseTaskStat> tasks) {
}
