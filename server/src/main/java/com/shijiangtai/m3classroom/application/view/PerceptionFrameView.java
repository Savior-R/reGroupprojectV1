package com.shijiangtai.m3classroom.application.view;

import java.util.Objects;
import java.util.UUID;

/** Acknowledgement that a screen frame was persisted and queued for OCR (02 §4.3). */
public record PerceptionFrameView(UUID frameId, String status) {

    public PerceptionFrameView {
        Objects.requireNonNull(frameId, "frameId");
        Objects.requireNonNull(status, "status");
    }
}
