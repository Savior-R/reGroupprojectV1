package com.shijiangtai.m5question.application;

import com.shijiangtai.shared.domain.SchoolId;

/** Lazy / event-driven seed initialization (FR-M5-01, FR-M5-05). */
public interface SeedService {

    /** Idempotently ensures the school has a library and seed data. */
    void ensureLibrary(SchoolId schoolId);

    /** Hook for M1's {@code SchoolCreatedEvent}; also idempotent. */
    void onSchoolCreated(SchoolId schoolId);
}
