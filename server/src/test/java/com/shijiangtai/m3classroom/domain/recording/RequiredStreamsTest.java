package com.shijiangtai.m3classroom.domain.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.task.TaskMode;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class RequiredStreamsTest {

    @Test
    void freeRequiresCameraScreenAudio() {
        assertThat(RequiredStreams.forMode(TaskMode.FREE))
                .containsExactlyInAnyOrder(
                        StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
        assertThat(RequiredStreams.forMode(TaskMode.SCENARIO))
                .containsExactlyInAnyOrder(
                        StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
    }

    @Test
    void interviewRequiresCameraAndAudio() {
        assertThat(RequiredStreams.forMode(TaskMode.INTERVIEW))
                .containsExactlyInAnyOrder(StreamType.CAMERA, StreamType.AUDIO)
                .doesNotContain(StreamType.SCREEN);
    }

    @Test
    void missingListsOnlyUploadedGaps() {
        assertThat(RequiredStreams.missing(
                EnumSet.of(StreamType.CAMERA, StreamType.AUDIO), TaskMode.FREE))
                .containsExactly(StreamType.SCREEN);
        assertThat(RequiredStreams.missing(EnumSet.of(StreamType.AUDIO), TaskMode.FREE))
                .containsExactly(StreamType.CAMERA, StreamType.SCREEN);
        assertThat(RequiredStreams.missing(EnumSet.noneOf(StreamType.class), TaskMode.FREE))
                .containsExactly(StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
        assertThat(RequiredStreams.missing(EnumSet.allOf(StreamType.class), TaskMode.FREE))
                .isEmpty();

        assertThat(RequiredStreams.missing(EnumSet.of(StreamType.CAMERA), TaskMode.INTERVIEW))
                .containsExactly(StreamType.AUDIO);
        assertThat(RequiredStreams.missing(EnumSet.allOf(StreamType.class), TaskMode.INTERVIEW))
                .isEmpty();
    }
}
