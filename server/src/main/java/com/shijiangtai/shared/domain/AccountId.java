package com.shijiangtai.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record AccountId(UUID value) {
    public AccountId {
        Objects.requireNonNull(value, "value");
    }
}
