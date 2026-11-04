package com.shijiangtai.m3classroom.application.port;

import java.util.Objects;

/**
 * Generated text plus provenance. {@code confidence} is {@code 0.0} for template fallbacks.
 */
public record ContentResponse(
        String text, double confidence, String modelVersion, String promptVersion) {

    public ContentResponse {
        Objects.requireNonNull(text, "text");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
