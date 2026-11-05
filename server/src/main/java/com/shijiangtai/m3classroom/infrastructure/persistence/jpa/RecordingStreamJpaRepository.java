package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingStreamJpaRepository
        extends JpaRepository<RecordingStreamEntity, UUID> {

    List<RecordingStreamEntity> findByRecordingId(UUID recordingId);

    Optional<RecordingStreamEntity> findByRecordingIdAndStreamType(
            UUID recordingId, String streamType);

    void deleteByRecordingId(UUID recordingId);
}
