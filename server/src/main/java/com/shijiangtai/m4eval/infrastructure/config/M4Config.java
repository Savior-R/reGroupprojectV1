package com.shijiangtai.m4eval.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * M4 wiring. Timers are enabled here rather than in the shared application config so the module
 * owns its own scheduler. Interval properties live in {@code application.yml} as
 * {@code m4.eval.*} and fall back to the documented defaults via {@code @Scheduled} placeholders.
 */
@Configuration
@EnableScheduling
public class M4Config {
}
