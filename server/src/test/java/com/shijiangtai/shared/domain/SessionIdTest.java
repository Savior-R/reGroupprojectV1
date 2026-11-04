package com.shijiangtai.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionIdTest {

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new SessionId(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityUsesUuid() {
        UUID id = UUID.randomUUID();
        assertThat(SessionId.of(id)).isEqualTo(new SessionId(id));
    }
}
