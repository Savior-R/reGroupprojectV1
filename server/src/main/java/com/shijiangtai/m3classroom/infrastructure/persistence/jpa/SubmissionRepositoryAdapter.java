package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SubmissionRepositoryAdapter implements SubmissionRepository {
    private final SubmissionJpaRepository repository;

    public SubmissionRepositoryAdapter(SubmissionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Submission save(Submission submission) {
        // Flush so the two unique constraints surface inside the submit transaction instead of
        // at an opaque commit-time failure.
        repository.saveAndFlush(toEntity(submission));
        return submission;
    }

    @Override
    public Optional<Submission> findByTaskAndStudent(
            TaskId taskId, AccountId studentAccountId) {
        return repository.findByTaskIdAndStudentAccountId(
                taskId.value(), studentAccountId.value()).map(this::toDomain);
    }

    @Override
    public Optional<Submission> findBySession(SessionId sessionId) {
        return repository.findBySessionId(sessionId.value()).map(this::toDomain);
    }

    @Override
    public boolean existsBySession(SessionId sessionId) {
        return repository.existsBySessionId(sessionId.value());
    }

    @Override
    public List<Submission> findExpiredForRetention(Instant now) {
        return repository.findExpiredForRetention(now).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void deleteBySession(SessionId sessionId) {
        repository.deleteBySessionId(sessionId.value());
        // Flush so a following session delete in the same transaction sees the
        // ON DELETE RESTRICT foreign key released.
        repository.flush();
    }

    private SubmissionEntity toEntity(Submission submission) {
        SubmissionEntity entity = new SubmissionEntity();
        entity.setId(submission.id());
        entity.setTaskId(submission.taskId().value());
        entity.setStudentAccountId(submission.studentAccountId().value());
        entity.setSessionId(submission.sessionId().value());
        entity.setClassIdSnapshot(submission.classIdSnapshot() == null
                ? null : submission.classIdSnapshot().value());
        entity.setSubmittedAt(submission.submittedAt());
        entity.setStatus(submission.status());
        return entity;
    }

    private Submission toDomain(SubmissionEntity entity) {
        return new Submission(
                entity.getId(),
                new TaskId(entity.getTaskId()),
                new AccountId(entity.getStudentAccountId()),
                new SessionId(entity.getSessionId()),
                entity.getClassIdSnapshot() == null
                        ? null : new ClassId(entity.getClassIdSnapshot()),
                entity.getSubmittedAt(),
                entity.getStatus());
    }
}
