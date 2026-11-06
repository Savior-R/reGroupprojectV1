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
import java.util.Optional;
import java.util.UUID;

public final class InMemorySubmissionRepository implements SubmissionRepository {

    private final Map<UUID, Submission> submissions = new LinkedHashMap<>();

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
     * The fake has no course-end data, so it never reports retention candidates on its own;
     * tests that need submitted retention seed those candidates explicitly.
     */
    @Override
    public synchronized List<Submission> findExpiredForRetention(Instant now) {
        return List.of();
    }

    @Override
    public synchronized void deleteBySession(SessionId sessionId) {
        submissions.values().removeIf(
                submission -> submission.sessionId().equals(sessionId));
    }

    public synchronized int size() {
        return submissions.size();
    }

    public synchronized void clear() {
        submissions.clear();
    }
}
