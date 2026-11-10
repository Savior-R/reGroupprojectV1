package com.shijiangtai.m6training.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only M3 task access used to publish templates. The published task snapshot is the
 * template's source of truth (08 §4.1); M6 never mutates the task.
 */
public interface SourceTaskPort {

    Optional<SourceTask> findTask(UUID taskId);

    record SourceTask(
            UUID id,
            UUID courseId,
            UUID schoolId,
            String mode,
            String status,
            int configVersion,
            String configSnapshotJson) {
    }
}
