package com.shijiangtai.m3classroom.domain.recording;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecordingStreamRepository {

    List<RecordingStream> findByRecording(UUID recordingId);

    Optional<RecordingStream> find(UUID recordingId, StreamType type);

    void deleteByRecording(UUID recordingId);
}
