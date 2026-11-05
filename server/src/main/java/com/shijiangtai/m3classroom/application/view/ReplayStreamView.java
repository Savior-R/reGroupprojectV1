package com.shijiangtai.m3classroom.application.view;

import java.util.Objects;

/** One uploaded recording stream and its presigned GET URL. */
public record ReplayStreamView(
        String streamType,
        String url,
        long offsetMs,
        long durationMs,
        String mime) {

    public ReplayStreamView {
        Objects.requireNonNull(streamType, "streamType");
        Objects.requireNonNull(url, "url");
    }
}
