package com.shijiangtai.m3classroom.domain.recording;

import java.util.Objects;
import java.util.UUID;

public final class RecordingObjectKeyBuilder {
    private static final String PREFIX = "recordings/";
    private static final String EXTENSION = ".webm";

    public String taskKey(UUID schoolId, UUID taskId, UUID sessionId, StreamType type) {
        Objects.requireNonNull(schoolId, "schoolId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        return PREFIX + schoolId + "/task/" + taskId + "/" + sessionId
                + "/" + type.wireValue() + EXTENSION;
    }

    public String trainingKey(UUID schoolId, UUID sessionId, StreamType type) {
        Objects.requireNonNull(schoolId, "schoolId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        return PREFIX + schoolId + "/training/" + sessionId + "/" + type.wireValue() + EXTENSION;
    }
}
