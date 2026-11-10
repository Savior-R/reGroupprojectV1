package com.shijiangtai.m7teacher.application.view;

import java.math.BigDecimal;
import java.util.UUID;

public record LeaderboardItem(
        int rank,
        UUID studentAccountId,
        String studentName,
        String className,
        BigDecimal aiTotal,
        BigDecimal teacherTotal,
        BigDecimal finalTotal) {
}
