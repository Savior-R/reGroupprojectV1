package com.shijiangtai.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record SchoolId(UUID value) {
    public SchoolId {
        Objects.requireNonNull(value, "value");
    }
}
