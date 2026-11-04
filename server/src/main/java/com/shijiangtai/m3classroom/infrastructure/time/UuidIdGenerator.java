package com.shijiangtai.m3classroom.infrastructure.time;

import com.shijiangtai.shared.application.IdGenerator;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UUID version 7 generator (05C §6.2): a 48-bit Unix-millisecond timestamp, the version and
 * RFC 4122 variant bits, and a 74-bit random tail. Time-ordered ids keep database inserts and
 * event scans roughly sequential without leaking a counter.
 */
public class UuidIdGenerator implements IdGenerator {

    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;
    private static final long VERSION_7 = 0x7L;
    private static final long VARIANT_RFC_4122 = 0b10L;

    @Override
    public UUID nextId() {
        long timestamp = System.currentTimeMillis() & TIMESTAMP_MASK;
        long randomA = ThreadLocalRandom.current().nextLong() & 0xFFFL;
        long randomB = ThreadLocalRandom.current().nextLong();
        long mostSignificantBits = (timestamp << 16) | (VERSION_7 << 12) | randomA;
        long leastSignificantBits = (VARIANT_RFC_4122 << 62) | (randomB >>> 2);
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
