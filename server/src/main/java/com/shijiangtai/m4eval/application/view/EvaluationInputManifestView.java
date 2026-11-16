package com.shijiangtai.m4eval.application.view;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal manifest (06 §9.3); consumed only by the Python worker. */
public record EvaluationInputManifestView(
        UUID evaluationId,
        UUID sessionId,
        String sourceType,
        UUID sourceId,
        String mode,
        SessionView session,
        Map<String, Object> config,
        List<RecordingView> recordings,
        CountsView counts,
        Map<String, Integer> weights,
        String rubricVersion,
        Map<String, Object> flags) {

    public record SessionView(
            String sessionType,
            String status,
            Long durationMs,
            Map<String, Object> streamOffsets,
            String startedAt,
            String endedAt) {
    }

    public record RecordingView(
            UUID streamId,
            String streamType,
            String url,
            Long durationMs,
            long offsetMs,
            String mime) {
    }

    public record CountsView(long texts, long frames, long events) {
    }
}
