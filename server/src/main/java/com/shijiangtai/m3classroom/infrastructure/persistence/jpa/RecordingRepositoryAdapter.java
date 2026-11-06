package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.RecordingStreamRepository;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the {@link Recording} aggregate: the recording row is upserted and each stream is
 * merged by its stable id, so a status update never duplicates the unique
 * {@code (recording_id, stream_type)} row.
 *
 * <p>The save is merge-only: streams missing from the passed aggregate are left untouched. The
 * domain never removes streams, and a concurrent start may have committed a sibling stream after
 * the caller read the aggregate, so deleting absent rows here would corrupt parallel recordings.
 * Explicit deletion goes through {@link RecordingRepository#deleteBySession} or
 * {@link RecordingStreamRepository}.
 */
@Repository
public class RecordingRepositoryAdapter implements RecordingRepository {
    private final RecordingJpaRepository recordingRepository;
    private final RecordingStreamJpaRepository streamEntityRepository;
    private final RecordingStreamRepository streamRepository;

    public RecordingRepositoryAdapter(
            RecordingJpaRepository recordingRepository,
            RecordingStreamJpaRepository streamEntityRepository,
            RecordingStreamRepository streamRepository) {
        this.recordingRepository = recordingRepository;
        this.streamEntityRepository = streamEntityRepository;
        this.streamRepository = streamRepository;
    }

    /**
     * Runs in its own transaction so a failed save (including a commit-time constraint failure)
     * cannot mark the caller's transaction rollback-only. Callers that must persist a failure
     * transition after a failed save (recording complete) depend on this isolation.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Recording save(Recording recording) {
        RecordingEntity entity = recordingRepository.findById(recording.id())
                .orElseGet(RecordingEntity::new);
        entity.setId(recording.id());
        entity.setSessionId(recording.sessionId().value());
        entity.setStatus(recording.status().wireValue());
        entity.setCreatedAt(recording.createdAt());
        entity.setUpdatedAt(recording.updatedAt());
        recordingRepository.save(entity);

        Map<UUID, RecordingStreamEntity> existing = new HashMap<>();
        for (RecordingStreamEntity streamEntity
                : streamEntityRepository.findByRecordingId(recording.id())) {
            existing.put(streamEntity.getId(), streamEntity);
        }
        for (RecordingStream stream : recording.streams()) {
            RecordingStreamEntity streamEntity = existing.get(stream.id());
            Instant createdAt = streamEntity == null
                    ? recording.updatedAt() : streamEntity.getCreatedAt();
            if (streamEntity == null) {
                streamEntity = new RecordingStreamEntity();
                streamEntity.setId(stream.id());
            }
            streamEntity.setRecordingId(recording.id());
            streamEntity.setStreamType(stream.type().wireValue());
            streamEntity.setUploadId(stream.uploadId());
            streamEntity.setObjectKey(stream.objectKey());
            streamEntity.setPartSize(stream.partSize());
            streamEntity.setPartsUploaded(stream.partsUploaded());
            streamEntity.setBytesUploaded(stream.bytesUploaded());
            streamEntity.setDurationMs(stream.durationMs());
            streamEntity.setOffsetMs(stream.offsetMs());
            streamEntity.setMime(stream.mime());
            streamEntity.setStatus(stream.status().wireValue());
            streamEntity.setCreatedAt(createdAt);
            streamEntity.setUpdatedAt(recording.updatedAt());
            streamEntityRepository.save(streamEntity);
        }
        return recording;
    }

    @Override
    public Optional<Recording> findBySession(SessionId sessionId) {
        return recordingRepository.findBySessionId(sessionId.value()).map(this::toDomain);
    }

    @Override
    public Optional<Recording> findById(UUID id) {
        return recordingRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteBySession(SessionId sessionId) {
        recordingRepository.findBySessionId(sessionId.value())
                .ifPresent(recordingRepository::delete);
    }

    private Recording toDomain(RecordingEntity entity) {
        return Recording.rehydrate(
                entity.getId(),
                new SessionId(entity.getSessionId()),
                RecordingStatus.fromWire(entity.getStatus()),
                streamRepository.findByRecording(entity.getId()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
