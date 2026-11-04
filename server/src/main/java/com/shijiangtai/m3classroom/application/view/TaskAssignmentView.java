package com.shijiangtai.m3classroom.application.view;

import java.time.Instant;
import java.util.UUID;

public record TaskAssignmentView(
        UUID studentAccountId,
        UUID classIdSnapshot,
        String status,
        Instant assignedAt) {
}
