package com.shijiangtai.m3classroom.api.dto;

import java.util.List;
import java.util.UUID;

public record QuestionConfigRequest(List<UUID> questionIds, int totalDurationSeconds) {
}
