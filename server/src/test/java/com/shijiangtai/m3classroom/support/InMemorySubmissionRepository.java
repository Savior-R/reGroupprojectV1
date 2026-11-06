package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.domain.submission.Submission;
import com.shijiangtai.m3classroom.domain.submission.SubmissionRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class InMemorySubmissionRepository implements SubmissionRepository {

    private final Map<UUID, Submission> submissions = new LinkedHashMap<>();
    private final Map<UUID, Submission> expiredCandidates = new LinkedHashMap<>();

    @Override
    public synchronized Submission save(Submission submission) {
        submissions.put(submission.id(), submission);
        return submission;
    }

    @Override
    public synchronized Optional<Submission> findByTaskAndStudent(
            TaskId taskId, AccountId studentAccountId) {
        return submissions.values().stream()
                .filter(submission -> submission.taskId().equals(taskId))
                .filter(submission -> submission.studentAccountId().equals(studentAccountId))
                .findFirst();
    }

    @Override
    public synchronized Optional<Submission> findBySession(SessionId sessionId) {
        return submissions.values().stream()
                .filter(submission -> submission.sessionId().equals(sessionId))
                .findFirst();
    }

    @Override
    public synchronized boolean existsBySession(SessionId sessionId) {
        return findBySession(sessionId).isPresent();
    }

    /**
     * The fake has no course-end data, so it only reports candidates seeded through
     * {@link #addExpired(Submission)}; without seeding it returns an empty list.
     */
    @Override
    public synchronized List<Submission> findExpiredForRetention(Instant now) {
        return List.copyOf(expiredCandidates.values());
    }

    /**
     * Test-only seeding of a submitted-retention candidate. The submission is also visible through
     * the regular query methods so deletion behaves like production.
     */
    public synchronized void addExpired(Submission submission) {
        Objects.requireNonNull(submission, "submission");
        submissions.put(submission.id(), submission);
        expiredCandidates.put(submission.id(), submission);
    }

    @Override
    public synchronized void deleteBySession(SessionId sessionId) {
        submissions.values().removeIf(
                submission -> submission.sessionId().equals(sessionId));
        expiredCandidates.values().removeIf(
                submission -> submission.sessionId().equals(sessionId));
    }

    public synchronized int size() {
        return submissions.size();
    }

    public synchronized void clear() {
        submissions.clear();
        expiredCandidates.clear();
    }
}
