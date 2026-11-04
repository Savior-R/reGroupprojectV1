package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TaskAssignment(
        UUID id,
        TaskId taskId,
        AccountId studentAccountId,
        ClassId classIdSnapshot,
        Instant assignedAt,
        AssignmentStatus status) {

    public TaskAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(studentAccountId, "studentAccountId");
        Objects.requireNonNull(assignedAt, "assignedAt");
        Objects.requireNonNull(status, "status");
    }
}
