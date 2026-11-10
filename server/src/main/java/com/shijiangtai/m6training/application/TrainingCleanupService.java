package com.shijiangtai.m6training.application;

public interface TrainingCleanupService {

    /** Marks records whose one-month retention elapsed, then requests physical cleanup. */
    void expireDueRecords();

    /** Retries physical cleanup for deleted / expired rows (idempotent). */
    void cleanupPendingRecords();

    /** Expires unused free-training materials after 24 hours. */
    void expireUnusedMaterials();
}
