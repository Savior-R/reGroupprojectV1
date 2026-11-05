package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.session.Session;
import com.shijiangtai.m3classroom.domain.session.SessionRepository;
import com.shijiangtai.m3classroom.domain.session.SessionStatus;
import com.shijiangtai.m3classroom.domain.session.SessionType;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepositoryAdapter implements SessionRepository {
    private static final List<String> ACTIVE_STATUSES = List.of(
            SessionStatus.PREPARING.wireValue(), SessionStatus.IN_PROGRESS.wireValue());

    private final SessionJpaRepository repository;
    private final SessionJsonCodec jsonCodec;

    public SessionRepositoryAdapter(SessionJpaRepository repository, SessionJsonCodec jsonCodec) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Session save(Session session) {
        // Flush so the partial unique active-session index surfaces here and can be
        // translated into SESSION_ACTIVE_EXISTS instead of an opaque commit-time 500.
        repository.saveAndFlush(toEntity(session));
        return session;
    }

    @Override
    public Optional<Session> findById(SessionId id) {
        return repository.findById(id.value()).map(this::toDomain);
    }

    @Override
    public Optional<Session> findActiveByStudentAndTask(
            AccountId studentAccountId, TaskId taskId) {
        return repository.findByStudentAccountIdAndTaskIdAndStatusInOrderByAttemptNoDesc(
                        studentAccountId.value(), taskId.value(), ACTIVE_STATUSES).stream()
                .findFirst()
                .map(this::toDomain);
    }

    @Override
    public List<Session> findByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return repository.findByStudentAccountIdAndTaskIdOrderByAttemptNoAsc(
                        studentAccountId.value(), taskId.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Session> findByStatusIn(Collection<SessionStatus> statuses) {
        List<String> wireStatuses = statuses.stream()
                .map(SessionStatus::wireValue)
                .toList();
        return repository.findByStatusIn(wireStatuses).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByStudentAndTask(AccountId studentAccountId, TaskId taskId) {
        return repository.countByStudentAccountIdAndTaskId(
                studentAccountId.value(), taskId.value());
    }

    private SessionEntity toEntity(Session session) {
        SessionEntity entity = new SessionEntity();
        entity.setId(session.id().value());
        entity.setSessionType(session.type().wireValue());
        entity.setTaskId(session.taskId().value());
        entity.setCourseId(session.courseId() == null ? null : session.courseId().value());
        entity.setStudentAccountId(session.studentAccountId().value());
        entity.setMode(session.mode().wireValue());
        entity.setStatus(session.status().wireValue());
        entity.setAttemptNo(session.attemptNo());
        entity.setStartedAt(session.startedAt());
        entity.setEndedAt(session.endedAt());
        entity.setDurationMs(session.durationMs());
        entity.setStreamOffsets(jsonCodec.writeStreamOffsets(session.streamOffsets()));
        entity.setCreatedAt(session.createdAt());
        entity.setUpdatedAt(session.updatedAt());
        return entity;
    }

    private Session toDomain(SessionEntity entity) {
        if (entity.getTaskId() == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR, "会话缺少任务关联",
                    Map.of("session_id", entity.getId().toString()));
        }
        return Session.rehydrate(
                new SessionId(entity.getId()),
                SessionType.fromWire(entity.getSessionType()),
                new TaskId(entity.getTaskId()),
                entity.getCourseId() == null ? null : new CourseId(entity.getCourseId()),
                new AccountId(entity.getStudentAccountId()),
                TaskMode.fromWire(entity.getMode()),
                SessionStatus.fromWire(entity.getStatus()),
                entity.getAttemptNo(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getDurationMs(),
                jsonCodec.readStreamOffsets(entity.getStreamOffsets()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
