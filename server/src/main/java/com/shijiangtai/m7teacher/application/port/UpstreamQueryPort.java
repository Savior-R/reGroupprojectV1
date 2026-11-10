package com.shijiangtai.m7teacher.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to M1/M2/M3/M4 (FR-M7-09). M7 never writes to these modules.
 *
 * <p>The production adapter reads the shared PostgreSQL database directly; the interface keeps
 * the application layer independent from the persistence choice and allows a fixture adapter
 * while M4 is still being built.
 */
public interface UpstreamQueryPort {

    record Course(
            UUID id, UUID schoolId, String name, String status, Instant endAt, Instant archivedAt) {
        public boolean archived() {
            return "archived".equals(status) || archivedAt != null;
        }
    }

    record Task(
            UUID id,
            UUID courseId,
            String mode,
            String topic,
            String status,
            Map<String, Integer> rubricWeights) {
    }

    record Assignment(
            UUID id,
            UUID taskId,
            UUID studentAccountId,
            UUID classIdSnapshot,
            Instant assignedAt,
            String status) {
    }

    record Submission(
            UUID id,
            UUID taskId,
            UUID studentAccountId,
            UUID sessionId,
            UUID classIdSnapshot,
            Instant submittedAt,
            String status) {
    }

    record Session(
            UUID id, UUID taskId, UUID studentAccountId, String status,
            Instant startedAt, Instant endedAt) {
    }

    record Evaluation(
            UUID id,
            UUID submissionId,
            UUID sessionId,
            String status,
            BigDecimal totalScore,
            Instant completedAt,
            Map<String, BigDecimal> dimensionScores,
            String errorCode,
            String errorMessage) {
    }

    record Account(UUID id, String username, UUID classId, UUID schoolId) {
    }

    Optional<Course> findCourse(UUID courseId);

    boolean isTeacherInGroup(UUID courseId, UUID teacherId);

    Optional<Task> findTask(UUID taskId);

    List<Task> findTasksByCourse(UUID courseId);

    List<Assignment> findAssignments(UUID taskId);

    Optional<Assignment> findAssignment(UUID taskId, UUID studentAccountId);

    List<Submission> findSubmissionsByTask(UUID taskId);

    Optional<Submission> findSubmission(UUID submissionId);

    List<Submission> findSubmissionsByStudent(UUID studentAccountId);

    Optional<Session> findSession(UUID sessionId);

    List<Session> findSessionsByTask(UUID taskId);

    Optional<Evaluation> findEvaluationBySubmission(UUID submissionId);

    Map<UUID, Evaluation> findEvaluationsBySubmissionIds(Collection<UUID> submissionIds);

    Map<UUID, Account> findAccounts(Collection<UUID> accountIds);

    Map<UUID, String> findClassNames(Collection<UUID> classIds);

    Map<UUID, String> findCourseStudentStatuses(UUID courseId, Collection<UUID> studentIds);

    /** Locks the M3 task row for the task-level critical sections (07 §4.2). */
    void lockTask(UUID taskId);

    List<UUID> findRecentSubmissionIds(int limit);
}
