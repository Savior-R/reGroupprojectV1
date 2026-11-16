package com.shijiangtai.m6training.application.event;

import java.util.UUID;

/** After-commit request to physically remove a deleted / expired record and its media. */
public record TrainingRecordCleanupRequestedEvent(UUID recordId) {
}
