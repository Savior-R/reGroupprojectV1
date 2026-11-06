package com.shijiangtai.m3classroom.persistence;

import static com.shijiangtai.m3classroom.TestFixtures.freeCommandWithStudent;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.application.RecordingApplicationService;
import com.shijiangtai.m3classroom.application.SessionApplicationService;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskMaterialService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.view.MultipartUploadView;
import com.shijiangtai.m3classroom.application.view.SessionView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.recording.Recording;
import com.shijiangtai.m3classroom.domain.recording.RecordingRepository;
import com.shijiangtai.m3classroom.domain.recording.RecordingStream;
import com.shijiangtai.m3classroom.domain.recording.StreamType;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real PostgreSQL concurrency regression for parallel camera/screen/audio multipart starts.
 *
 * <p>Without the per-session row lock the three requests race on the recording aggregate: two
 * transactions can both create a recording row (violating {@code uq_recordings_session}) or each
 * save an aggregate that does not contain the sibling streams. The lock makes start/complete/abort
 * serialize per session; every start must therefore see the streams committed by its predecessor.
 */
class RecordingConcurrencyIT extends PostgresIntegrationTest {

    private static final int THREADS = 3;

    @Autowired
    private TaskApplicationService taskApplicationService;

    @Autowired
    private TaskMaterialService materialService;

    @Autowired
    private TaskPublishService publishService;

    @Autowired
    private SessionApplicationService sessionApplicationService;

    @Autowired
    private RecordingApplicationService recordingApplicationService;

    @Autowired
    private RecordingRepository recordingRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void threeSimultaneousStartsKeepOneRecordingWithThreeStreams() throws Exception {
        TaskView task = taskApplicationService.createDraft(teacher(), freeCommandWithStudent());
        TaskId taskId = new TaskId(task.taskId());
        materialService.upload(
                teacher(), taskId, "a.txt", "text/plain",
                "这是一份足够长的参考材料文本内容，用于测试发布。"
                        .getBytes(StandardCharsets.UTF_8));
        publishService.publish(teacher(), taskId);
        SessionView created = sessionApplicationService.createSession(student(), taskId);
        SessionId sessionId = new SessionId(created.sessionId());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MultipartUploadView>> starts = new ArrayList<>();
            for (StreamType type : List.of(
                    StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO)) {
                starts.add(pool.submit(() -> {
                    ready.countDown();
                    if (!go.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrent start barrier timed out");
                    }
                    return recordingApplicationService.start(student(), sessionId, type);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<MultipartUploadView> start : starts) {
                MultipartUploadView upload = start.get(30, TimeUnit.SECONDS);
                assertThat(upload.uploadId()).isNotBlank();
                assertThat(upload.objectKey()).isNotBlank();
            }
        } finally {
            pool.shutdownNow();
        }

        Recording recording = recordingRepository.findBySession(sessionId).orElseThrow();
        assertThat(recording.streams())
                .extracting(RecordingStream::type)
                .containsExactlyInAnyOrder(
                        StreamType.CAMERA, StreamType.SCREEN, StreamType.AUDIO);
        assertThat(recording.streams()).hasSize(3);
        Integer recordings = jdbc.queryForObject(
                "SELECT count(*) FROM m3_classroom.recordings WHERE session_id = ?",
                Integer.class, sessionId.value());
        Integer streams = jdbc.queryForObject("""
                SELECT count(*) FROM m3_classroom.recording_streams
                WHERE recording_id = ?
                """, Integer.class, recording.id());
        assertThat(recordings).isEqualTo(1);
        assertThat(streams).isEqualTo(3);
    }
}
