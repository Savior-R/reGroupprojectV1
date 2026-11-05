package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Re-checks task/course start preconditions at start time (05A §4.3).
 *
 * <p>Creation already validates the course window, but a session may sit in {@code preparing}
 * for a long time. A course archived or past {@code end_at} after creation must not allow the
 * session to start; the normal state check is {@code preparing}.
 */
@Component
public class SessionStartGuard {

    private final SessionRepository sessionRepository;
    private final TaskRepository taskRepository;
    private final CourseQueryPort courseQueryPort;
    private final ClockProvider clock;

    public SessionStartGuard(
            SessionRepository sessionRepository,
            TaskRepository taskRepository,
            CourseQueryPort courseQueryPort,
            ClockProvider clock) {
        this.sessionRepository = Objects.requireNonNull(
                sessionRepository, "sessionRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.courseQueryPort = Objects.requireNonNull(courseQueryPort, "courseQueryPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Loads the session, then applies the task/course start preconditions. */
    public Session requireStartable(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.RESOURCE_NOT_FOUND, "会话不存在",
                        Map.of("session_id", sessionId.value().toString())));
        requireStartable(session);
        return session;
    }

    /** Validates an already-loaded session aggregate, its task and its course. */
    public void requireStartable(Session session) {
        Objects.requireNonNull(session, "session");
        if (session.status() != SessionStatus.PREPARING) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("action", "session.start");
            details.put("status", session.status().wireValue());
            details.put("mode", session.mode().wireValue());
            throw new DomainException(ErrorCode.SESSION_STATE_INVALID,
                    "当前状态不允许该操作", details);
        }

        Task task = taskRepository.findById(session.taskId())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.RESOURCE_NOT_FOUND, "任务不存在",
                        Map.of("task_id", session.taskId().value().toString())));
        CourseId courseId = session.courseId() == null ? task.courseId() : session.courseId();
        CourseInfo course = courseQueryPort.findCourse(courseId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.RESOURCE_NOT_FOUND, "课程不存在",
                        Map.of("course_id", courseId.value().toString())));
        if (!course.active()) {
            throw new DomainException(ErrorCode.COURSE_ARCHIVED, "课程已归档",
                    Map.of("course_id", course.id().toString()));
        }
        Instant now = clock.now();
        if (course.endAt() != null && !course.endAt().isAfter(now)) {
            throw new DomainException(ErrorCode.COURSE_ENDED, "课程已结束",
                    Map.of("course_id", course.id().toString()));
        }
    }
}
