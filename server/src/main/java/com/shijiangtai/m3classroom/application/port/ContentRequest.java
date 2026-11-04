package com.shijiangtai.m3classroom.application.port;

import java.util.List;
import java.util.Objects;

/**
 * Context window sent to the content service (05A §7.2). Reference material and answer points
 * are intentionally absent so a virtual student cannot reveal them.
 */
public record ContentRequest(
        String taskId,
        String sessionId,
        String mode,
        String topic,
        List<String> recentTexts,
        String screenText,
        String studentName,
        String personality,
        double ability,
        String question) {

    public ContentRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(mode, "mode");
        recentTexts = recentTexts == null ? List.of() : List.copyOf(recentTexts);
    }
}
