package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStatus;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.RecordingStreamRepository;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the {@link Recording} aggregate: the recording row is upserted and each stream is
 * merged by its stable id, so a status update never duplicates the unique
 * {@code (recording_id, stream_type)} row. Streams removed from the aggregate are deleted.
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

    @Override
    @Transactional
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
        Set<UUID> aggregateIds = recording.streams().stream()
                .map(RecordingStream::id)
                .collect(Collectors.toSet());
        List<RecordingStreamEntity> stale = existing.values().stream()
                .filter(streamEntity -> !aggregateIds.contains(streamEntity.getId()))
                .toList();
        if (!stale.isEmpty()) {
            streamEntityRepository.deleteAll(stale);
            // Flush the deletes before inserting replacements so the unique
            // (recording_id, stream_type) constraint is never checked against a doomed row.
            streamEntityRepository.flush();
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
