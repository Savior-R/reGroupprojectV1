package com.shijiangtai.m3classroom.application.command;

import java.util.Objects;

/** In-memory reference material supplied with a task create / update request. */
public record MaterialUpload(String filename, String mime, byte[] content) {
    public MaterialUpload {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(content, "content");
    }
}
