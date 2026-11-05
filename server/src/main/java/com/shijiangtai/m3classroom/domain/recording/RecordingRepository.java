package com.shijiangtai.m3classroom.domain.recording;

import com.shijiangtai.shared.domain.SessionId;
import java.util.Optional;
import java.util.UUID;

public interface RecordingRepository {

    Recording save(Recording recording);

    Optional<Recording> findBySession(SessionId sessionId);

    Optional<Recording> findById(UUID id);

    /** Deletes the recording row of a superseded attempt; streams cascade. */
    void deleteBySession(SessionId sessionId);
}
