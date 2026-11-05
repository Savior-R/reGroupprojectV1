package com.shijiangtai.m3classroom.api;

import com.shijiangtai.shared.domain.SessionId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Path/query mapping for {@link ReplayController}. */
@Component
public class ReplayRequestMapper {

    public SessionId toSessionId(UUID value) {
        return new SessionId(Objects.requireNonNull(value, "sessionId"));
    }
}
