package com.shijiangtai.m4eval.infrastructure.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shijiangtai.m4eval.application.port.EvaluationQueue.EvaluationDispatchMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis Streams producer contract (06 §5.1, M4-T03).
 *
 * <p>The message carries IDs only; the Python worker parses it with
 * {@code JobMessage}. Key names and the string-encoded attempt are therefore part of the
 * cross-language contract and are asserted here.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisEvaluationQueueTest {

    private static final UUID JOB_ID =
            UUID.fromString("22222222-bbbb-bbbb-bbbb-222222222222");
    private static final UUID EVALUATION_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-111111111111");
    private static final UUID SOURCE_ID =
            UUID.fromString("33333333-cccc-cccc-cccc-333333333333");
    private static final UUID SESSION_ID =
            UUID.fromString("44444444-dddd-dddd-dddd-444444444444");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-11-23T08:00:00Z");

    private StreamOperations<String, Object, Object> streamOps;
    private StringRedisTemplate redis;
    private RedisEvaluationQueue queue;

    @BeforeEach
    void setUp() {
        streamOps = mock(StreamOperations.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        queue = new RedisEvaluationQueue(redis);
    }

    @Test
    void publishWritesTheDocumentedFieldsToTheJobsStream() {
        queue.publish(message(2));

        ArgumentCaptor<MapRecord> record = ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> options = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamOps).add(record.capture(), options.capture());

        assertThat(record.getValue().getStream()).isEqualTo(RedisEvaluationQueue.STREAM);
        Map<String, String> fields = (Map<String, String>) record.getValue().getValue();
        assertThat(fields)
                .containsEntry("job_id", JOB_ID.toString())
                .containsEntry("evaluation_id", EVALUATION_ID.toString())
                .containsEntry("source_type", "task_submission")
                .containsEntry("source_id", SOURCE_ID.toString())
                .containsEntry("session_id", SESSION_ID.toString())
                .containsEntry("attempt", "2")
                .containsEntry("published_at", "2026-11-23T08:00:00Z");
        assertThat(options.getValue().getMaxlen()).isEqualTo(50_000L);
        assertThat(options.getValue().isApproximateTrimming()).isTrue();
    }

    @Test
    void deadLetterKeepsTheJobFieldsAndAddsTheReason() {
        queue.deadLetter(message(3), "attempts exhausted");

        ArgumentCaptor<MapRecord> record = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(record.capture(), any(XAddOptions.class));

        assertThat(record.getValue().getStream())
                .isEqualTo(RedisEvaluationQueue.DEAD_STREAM);
        Map<String, String> fields = (Map<String, String>) record.getValue().getValue();
        assertThat(fields)
                .containsEntry("job_id", JOB_ID.toString())
                .containsEntry("attempt", "3")
                .containsEntry("reason", "attempts exhausted");
    }

    @Test
    void deadLetterStoresAnEmptyReasonInsteadOfNull() {
        queue.deadLetter(message(1), null);

        ArgumentCaptor<MapRecord> record = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(record.capture(), any(XAddOptions.class));

        Map<String, String> fields = (Map<String, String>) record.getValue().getValue();
        assertThat(fields).containsEntry("reason", "");
    }

    @Test
    void deadLetterEntriesExpireAfterThirtyDays() {
        queue.deadLetter(message(1), "timeout");

        verify(redis).expire(RedisEvaluationQueue.DEAD_STREAM, Duration.ofDays(30));
    }

    private static EvaluationDispatchMessage message(int attempt) {
        return new EvaluationDispatchMessage(
                JOB_ID, EVALUATION_ID, "task_submission", SOURCE_ID, SESSION_ID,
                attempt, PUBLISHED_AT);
    }
}
