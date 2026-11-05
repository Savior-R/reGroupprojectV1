package com.shijiangtai.m3classroom.domain.recording;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingObjectKeyBuilderTest {
    private static final UUID SCHOOL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TASK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SESSION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final RecordingObjectKeyBuilder builder = new RecordingObjectKeyBuilder();

    @Test
    void taskKeyMatchesSpec() {
        assertThat(builder.taskKey(SCHOOL_ID, TASK_ID, SESSION_ID, StreamType.SCREEN))
                .isEqualTo("recordings/11111111-1111-1111-1111-111111111111"
                        + "/task/22222222-2222-2222-2222-222222222222"
                        + "/33333333-3333-3333-3333-333333333333/screen.webm");
    }

    @Test
    void trainingKeyMatchesSpec() {
        assertThat(builder.trainingKey(SCHOOL_ID, SESSION_ID, StreamType.AUDIO))
                .isEqualTo("recordings/11111111-1111-1111-1111-111111111111"
                        + "/training/33333333-3333-3333-3333-333333333333/audio.webm");
    }
}
