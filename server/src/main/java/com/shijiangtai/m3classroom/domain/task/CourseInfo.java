package com.shijiangtai.m3classroom.domain.task;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CourseInfo(
        UUID id,
        UUID schoolId,
        String name,
        boolean active,
        Instant endAt,
        List<UUID> teacherAccountIds) {

    public CourseInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(schoolId, "schoolId");
        Objects.requireNonNull(name, "name");
        teacherAccountIds = teacherAccountIds == null ? List.of() : List.copyOf(teacherAccountIds);
    }
}
