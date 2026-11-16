package com.shijiangtai.m4eval.infrastructure.queue;

import com.shijiangtai.m4eval.application.port.EvaluationQueue;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Streams producer (06 §5.1). The message carries IDs only; the worker pulls the manifest
 * and paged input from the internal HTTP API.
 */
@Component
public class RedisEvaluationQueue implements EvaluationQueue {

    public static final String STREAM = "evaluation:jobs";
    public static final String DEAD_STREAM = "evaluation:dead";
    private static final long MAX_LENGTH = 50_000;

    private final StringRedisTemplate redis;

    public RedisEvaluationQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void publish(EvaluationDispatchMessage message) {
        redis.opsForStream().add(
                StreamRecords.newRecord().in(STREAM).ofMap(fields(message)),
                XAddOptions.maxlen(MAX_LENGTH).approximateTrimming(true));
    }

    @Override
    public void deadLetter(EvaluationDispatchMessage message, String reason) {
        Map<String, String> fields = fields(message);
        fields.put("reason", reason == null ? "" : reason);
        redis.opsForStream().add(
                StreamRecords.newRecord().in(DEAD_STREAM).ofMap(fields),
                XAddOptions.maxlen(MAX_LENGTH).approximateTrimming(true));
        // Dead entries are kept for inspection for 30 days.
        redis.expire(DEAD_STREAM, Duration.ofDays(30));
    }

    private static Map<String, String> fields(EvaluationDispatchMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("job_id", message.jobId().toString());
        fields.put("evaluation_id", message.evaluationId().toString());
        fields.put("source_type", message.sourceType());
        fields.put("source_id", message.sourceId().toString());
        fields.put("session_id", message.sessionId().toString());
        fields.put("attempt", Integer.toString(message.attempt()));
        fields.put("published_at", message.publishedAt().toString());
        return fields;
    }
}
