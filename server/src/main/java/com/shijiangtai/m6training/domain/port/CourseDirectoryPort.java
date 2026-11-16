package com.shijiangtai.m6training.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only M2 access. Implemented with a JDBC adapter until M2 exposes a module API.
 */
public interface CourseDirectoryPort {

    Optional<CourseInfo> findCourse(UUID courseId);

    boolean isActiveTeacher(UUID courseId, UUID accountId);

    boolean isActiveStudent(UUID courseId, UUID studentAccountId);

    record CourseInfo(UUID id, UUID schoolId, String name, boolean active, Instant endAt) {
    }
}
