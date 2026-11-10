package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;

public record CourseStatsSummary(
        int tasks, int assigned, int submitted, int completed, BigDecimal averageFinal) {
}
