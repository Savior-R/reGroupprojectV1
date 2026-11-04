package com.shijiangtai.m3classroom.api;

import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Path-variable to domain-id mapping for {@link SessionController}. */
@Component
public class SessionRequestMapper {

    public TaskId toTaskId(UUID value) {
        return new TaskId(Objects.requireNonNull(value, "taskId"));
    }

    public SessionId toSessionId(UUID value) {
        return new SessionId(Objects.requireNonNull(value, "sessionId"));
    }
}
