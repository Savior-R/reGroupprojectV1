package com.shijiangtai.m4eval.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Integration event M6 publishes after a TrainingRecord is selected (11 §B7, 14 §事件表).
 *
 * <p>Payload follows the documented contract: {@code record_id}, {@code session_id},
 * {@code student_id}. Configuration and course data are read by M4 from the training tables at
 * input-assembly time, not carried in the event.
 */
public record TrainingRecordSelectedEvent(
        UUID trainingRecordId, UUID sessionId, UUID studentAccountId) {

    public TrainingRecordSelectedEvent {
        Objects.requireNonNull(trainingRecordId, "trainingRecordId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(studentAccountId, "studentAccountId");
    }
}
