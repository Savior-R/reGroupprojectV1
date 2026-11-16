package com.shijiangtai.m7teacher.application.command;

import com.shijiangtai.m7teacher.domain.ScoringMode;

public record UpdateGradingConfigCommand(
        int aiWeight, int teacherWeight, ScoringMode scoringMode, int configVersion) {
}
