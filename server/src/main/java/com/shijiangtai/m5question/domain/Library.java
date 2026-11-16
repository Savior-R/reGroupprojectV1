package com.shijiangtai.m5question.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One question-bank library per school (04 §4). */
public final class Library {
    private final UUID id;
    private final UUID schoolId;
    private final String seedVersion;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Library(UUID id, UUID schoolId, String seedVersion, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.schoolId = Objects.requireNonNull(schoolId, "schoolId");
        this.seedVersion = Objects.requireNonNull(seedVersion, "seedVersion");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Library create(UUID id, UUID schoolId, String seedVersion, Instant now) {
        return new Library(id, schoolId, seedVersion, now, now);
    }

    public static Library rehydrate(
            UUID id, UUID schoolId, String seedVersion, Instant createdAt, Instant updatedAt) {
        return new Library(id, schoolId, seedVersion, createdAt, updatedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID schoolId() {
        return schoolId;
    }

    public String seedVersion() {
        return seedVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
