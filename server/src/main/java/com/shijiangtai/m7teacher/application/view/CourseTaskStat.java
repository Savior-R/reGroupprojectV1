package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;
import java.util.UUID;

public record CourseTaskStat(
        UUID taskId,
        String topic,
        String mode,
        int assigned,
        int submitted,
        int completed,
        BigDecimal averageFinal) {
}
