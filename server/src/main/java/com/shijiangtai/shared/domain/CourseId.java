package com.shijiangtai.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record CourseId(UUID value) {
    public CourseId {
        Objects.requireNonNull(value, "value");
    }
}
