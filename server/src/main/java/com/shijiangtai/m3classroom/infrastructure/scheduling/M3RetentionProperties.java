package com.shijiangtai.m3classroom.infrastructure.scheduling;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retention scheduler configuration ({@code m3.retention.*}). */
@ConfigurationProperties(prefix = "m3.retention")
public record M3RetentionProperties(
        Boolean enabled,
        Duration initialDelay,
        Duration interval) {

    public M3RetentionProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        initialDelay = initialDelay == null ? Duration.ofMinutes(1) : initialDelay;
        interval = interval == null ? Duration.ofHours(1) : interval;
    }
}
