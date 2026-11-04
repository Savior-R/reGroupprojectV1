package com.shijiangtai.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record ClassId(UUID value) {
    public ClassId {
        Objects.requireNonNull(value, "value");
    }
}
