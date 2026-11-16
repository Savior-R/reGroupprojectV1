package com.shijiangtai.m6training.application.view;

import java.util.UUID;

/** Result of selecting an attempt; the auto-deleted oldest record id may be null. */
public record TrainingRecordSelectionView(
        TrainingRecordView record, UUID deletedRecordId) {
}
