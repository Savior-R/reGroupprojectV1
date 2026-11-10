package com.shijiangtai.m6training.domain.event;

import com.shijiangtai.m6training.domain.TrainingEffectiveConfig;
import com.shijiangtai.m6training.domain.TrainingLevel;
import java.util.UUID;

/**
 * After-commit signal for M4 to create a {@code training_record} evaluation (08 §8).
 *
 * <p>M4 does not exist yet; the event is the contract seam. It carries the immutable effective
 * configuration so M4 never reads mutable template state.
 */
public record TrainingRecordSelectedEvent(
        UUID recordId,
        UUID sessionId,
        UUID studentAccountId,
        UUID courseId,
        TrainingLevel trainingLevel,
        TrainingEffectiveConfig config) {
}
