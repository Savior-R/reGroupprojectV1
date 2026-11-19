package com.shijiangtai.m3classroom.application.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only account → school lookup used by the training-session runtime.
 *
 * <p>Task sessions carry the school in their published task snapshot. Training sessions
 * have no task, so M3 resolves the owning student's school for the recording object key.
 */
public interface SchoolDirectoryPort {

    Optional<UUID> findSchoolByAccount(UUID accountId);
}
