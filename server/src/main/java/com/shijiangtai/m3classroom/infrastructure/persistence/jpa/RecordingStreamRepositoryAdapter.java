package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.RecordingStreamRepository;
import com.shijiangtai.m3classroom.domain.recording.StreamStatus;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RecordingStreamRepositoryAdapter implements RecordingStreamRepository {
    private final RecordingStreamJpaRepository repository;

    public RecordingStreamRepositoryAdapter(RecordingStreamJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<RecordingStream> findByRecording(UUID recordingId) {
        return repository.findByRecordingId(recordingId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<RecordingStream> find(UUID recordingId, StreamType type) {
        return repository.findByRecordingIdAndStreamType(recordingId, type.wireValue())
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteByRecording(UUID recordingId) {
        repository.deleteByRecordingId(recordingId);
    }

    private RecordingStream toDomain(RecordingStreamEntity entity) {
        return new RecordingStream(
                entity.getId(),
                entity.getRecordingId(),
                StreamType.fromWire(entity.getStreamType()),
                entity.getUploadId(),
                entity.getObjectKey(),
                entity.getPartSize(),
                entity.getPartsUploaded(),
                entity.getBytesUploaded(),
                entity.getDurationMs(),
                entity.getOffsetMs(),
                entity.getMime(),
                StreamStatus.fromWire(entity.getStatus()));
    }
}
