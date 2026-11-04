package com.shijiangtai.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record QuestionId(UUID value) {
    public QuestionId {
        Objects.requireNonNull(value, "value");
    }
}
