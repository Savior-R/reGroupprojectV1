package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.domain.TrainingSessionStatus;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort.ClassroomSession;
import com.shijiangtai.shared.application.ClockProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** System-side session transitions used by the maintenance scheduler. */
@Component
public class TrainingSessionLifecycle {
    private final TrainingClassroomPort classroomPort;
    private final ClockProvider clock;

    public TrainingSessionLifecycle(TrainingClassroomPort classroomPort, ClockProvider clock) {
        this.classroomPort = classroomPort;
        this.clock = clock;
    }

    /** Ends an in-progress session at the hard deadline; idempotent for terminal states. */
    public boolean endAutomatically(UUID sessionId) {
        Optional<ClassroomSession> found = classroomPort.find(sessionId);
        if (found.isEmpty()) {
            return false;
        }
        ClassroomSession session = found.get();
        if (!TrainingSessionStatus.IN_PROGRESS.wireValue().equals(session.status())) {
            return false;
        }
        Instant now = clock.now();
        Long durationMs = session.startedAt() == null
                ? null : Math.max(0L, Duration.between(session.startedAt(), now).toMillis());
        classroomPort.updateStatus(
                sessionId, TrainingSessionStatus.ENDED.wireValue(), now, durationMs);
        return true;
    }
}
