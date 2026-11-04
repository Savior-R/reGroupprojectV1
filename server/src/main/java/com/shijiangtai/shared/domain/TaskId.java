package com.shijiangtai.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record TaskId(UUID value) {
    public TaskId {
        Objects.requireNonNull(value, "value");
    }
}
