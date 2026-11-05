package com.shijiangtai.m3classroom.domain.recording;

import com.shijiangtai.m3classroom.domain.task.TaskMode;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RequiredStreams {
    private static final Set<StreamType> FREE_MODE_STREAMS = Collections.unmodifiableSet(
            EnumSet.of(StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO));
    private static final Set<StreamType> INTERVIEW_MODE_STREAMS = Collections.unmodifiableSet(
            EnumSet.of(StreamType.CAMERA, StreamType.AUDIO));

    private RequiredStreams() {
    }

    public static Set<StreamType> forMode(TaskMode mode) {
        Objects.requireNonNull(mode, "mode");
        return mode == TaskMode.INTERVIEW ? INTERVIEW_MODE_STREAMS : FREE_MODE_STREAMS;
    }

    public static List<StreamType> missing(Set<StreamType> uploaded, TaskMode mode) {
        Objects.requireNonNull(uploaded, "uploaded");
        return forMode(mode).stream()
                .filter(type -> !uploaded.contains(type))
                .toList();
    }
}
