package com.shijiangtai.m3classroom.application.port;

import java.util.Objects;

public class MaterialExtractionException extends RuntimeException {
    private final MaterialExtractionError error;

    public MaterialExtractionException(MaterialExtractionError error, String message) {
        this(error, message, null);
    }

    public MaterialExtractionException(
            MaterialExtractionError error, String message, Throwable cause) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "error");
    }

    public MaterialExtractionError error() {
        return error;
    }
}
