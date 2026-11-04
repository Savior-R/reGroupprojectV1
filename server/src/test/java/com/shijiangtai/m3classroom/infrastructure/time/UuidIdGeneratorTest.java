package com.shijiangtai.m3classroom.infrastructure.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidIdGeneratorTest {

    private static final long TIMESTAMP_MASK = 0xFFFFFFFFFFFFL;

    private final UuidIdGenerator generator = new UuidIdGenerator();

    @Test
    void producesUuidVersion7() {
        for (int i = 0; i < 1_000; i++) {
            UUID id = generator.nextId();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        }
    }

    @Test
    void idsAreUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(generator.nextId());
        }
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void timestampEncodesCurrentUnixMilliseconds() {
        long before = System.currentTimeMillis() & TIMESTAMP_MASK;
        long encoded = generator.nextId().getMostSignificantBits() >>> 16;
        long after = System.currentTimeMillis() & TIMESTAMP_MASK;

        assertThat(encoded).isBetween(before, after);
    }
}
