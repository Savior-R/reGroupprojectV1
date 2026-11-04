package com.shijiangtai.shared.domain;

import java.util.Objects;
import java.util.UUID;

public record SessionId(UUID value) {
    public SessionId {
        Objects.requireNonNull(value, "value");
    }

    public static SessionId of(UUID value) {
        return new SessionId(value);
    }
}
