package com.shijiangtai.m3classroom.infrastructure.perception;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the Python perception service ({@code m3.ai.*}). */
@ConfigurationProperties(prefix = "m3.ai")
public record PerceptionAiProperties(
        String baseUrl,
        String serviceToken,
        Duration ocrTimeout,
        Duration classifyTimeout,
        Duration asrConnectTimeout) {

    private static final Duration DEFAULT_OCR_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_CLASSIFY_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    public PerceptionAiProperties {
        ocrTimeout = ocrTimeout == null ? DEFAULT_OCR_TIMEOUT : ocrTimeout;
        classifyTimeout = classifyTimeout == null ? DEFAULT_CLASSIFY_TIMEOUT : classifyTimeout;
        asrConnectTimeout =
                asrConnectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : asrConnectTimeout;
    }
}
