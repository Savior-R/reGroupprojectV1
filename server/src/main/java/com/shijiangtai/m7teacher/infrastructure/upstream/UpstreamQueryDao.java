package com.shijiangtai.m7teacher.infrastructure.upstream;

import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.m7teacher.infrastructure.persistence.jpa.M7JsonCodec;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * Read-only SQL adapter for M1/M2/M3/M4 (FR-M7-09 / 07 §2).
 *
 * <p>This adapter never issues an INSERT / UPDATE / DELETE against a non-M7 schema. The only
 * row lock it takes is {@code SELECT ... FOR UPDATE} on the M3 task, the lock order mandated by
 * 07 §4.2 for configuration changes, clearing scores and the first teacher score.
 */
@Repository
public class UpstreamQueryDao implements UpstreamQueryPort {

    private final JdbcTemplate jdbc;
    private final M7JsonCodec jsonCodec;

    @PersistenceContext
    private EntityManager entityManager;

    public UpstreamQueryDao(JdbcTemplate jdbc, M7JsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Optional<Course> findCourse(UUID courseId) {
        List<Course> rows = jdbc.query("""
                SELECT id, school_id, name, status, end_at, archived_at
                  FROM m2_course.courses
                 WHERE id = ?
                """, (rs, rowNum) -> new Course(
                        uuid(rs, "id"), uuid(rs, "school_id"), rs.getString("name"),
                        rs.getString("status"), instant(rs, "end_at"),
                        instant(rs, "archived_at")), courseId);
        return rows.stream().findFirst();
    }

    @Override
    public boolean isTeacherInGroup(UUID courseId, UUID teacherId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM m2_course.course_teachers
                 WHERE course_id = ? AND account_id = ? AND status = 'active'
                """, Integer.class, courseId, teacherId);
        return count != null && count > 0;
    }

    @Override
    public Optional<Task> findTask(UUID taskId) {
        List<Task> rows = jdbc.query("""
                SELECT id, course_id, mode, topic, status,
                       rubric_weights::text AS rubric_weights,
                       config_snapshot::text AS config_snapshot
                  FROM m3_classroom.tasks
                 WHERE id = ?
                """, (rs, rowNum) -> task(rs), taskId);
        return rows.stream().findFirst();
    }

    @Override
    public List<Task> findTasksByCourse(UUID courseId) {
        return jdbc.query("""
                SELECT id, course_id, mode, topic, status,
                       rubric_weights::text AS rubric_weights,
                       config_snapshot::text AS config_snapshot
                  FROM m3_classroom.tasks
                 WHERE course_id = ?
                 ORDER BY created_at, id
                """, (rs, rowNum) -> task(rs), courseId);
    }

    @Override
    public List<Assignment> findAssignments(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, student_account_id, class_id_snapshot,
                       assigned_at, status
                  FROM m3_classroom.task_assignments
                 WHERE task_id = ?
                 ORDER BY assigned_at, student_account_id
                """, (rs, rowNum) -> new Assignment(
                        uuid(rs, "id"), uuid(rs, "task_id"), uuid(rs, "student_account_id"),
                        uuid(rs, "class_id_snapshot"), instant(rs, "assigned_at"),
                        rs.getString("status")), taskId);
    }

    @Override
    public Optional<Assignment> findAssignment(UUID taskId, UUID studentAccountId) {
        List<Assignment> rows = jdbc.query("""
                SELECT id, task_id, student_account_id, class_id_snapshot,
                       assigned_at, status
                  FROM m3_classroom.task_assignments
                 WHERE task_id = ? AND student_account_id = ?
                """, (rs, rowNum) -> new Assignment(
                        uuid(rs, "id"), uuid(rs, "task_id"), uuid(rs, "student_account_id"),
                        uuid(rs, "class_id_snapshot"), instant(rs, "assigned_at"),
                        rs.getString("status")), taskId, studentAccountId);
        return rows.stream().findFirst();
    }

    @Override
    public List<Submission> findSubmissionsByTask(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, student_account_id, session_id, class_id_snapshot,
                       submitted_at, status
                  FROM m3_classroom.submissions
                 WHERE task_id = ?
                 ORDER BY submitted_at
                """, (rs, rowNum) -> submission(rs), taskId);
    }

    @Override
    public Optional<Submission> findSubmission(UUID submissionId) {
        List<Submission> rows = jdbc.query("""
                SELECT id, task_id, student_account_id, session_id, class_id_snapshot,
                       submitted_at, status
                  FROM m3_classroom.submissions
                 WHERE id = ?
                """, (rs, rowNum) -> submission(rs), submissionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<Submission> findSubmissionsByStudent(UUID studentAccountId) {
        return jdbc.query("""
                SELECT id, task_id, student_account_id, session_id, class_id_snapshot,
                       submitted_at, status
                  FROM m3_classroom.submissions
                 WHERE student_account_id = ?
                 ORDER BY submitted_at DESC
                """, (rs, rowNum) -> submission(rs), studentAccountId);
    }

    @Override
    public Optional<Session> findSession(UUID sessionId) {
        List<Session> rows = jdbc.query("""
                SELECT id, task_id, student_account_id, status, started_at, ended_at
                  FROM m3_classroom.sessions
                 WHERE id = ?
                """, (rs, rowNum) -> session(rs), sessionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<Session> findSessionsByTask(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, student_account_id, status, started_at, ended_at
                  FROM m3_classroom.sessions
                 WHERE task_id = ? AND session_type = 'task'
                 ORDER BY created_at DESC, id
                """, (rs, rowNum) -> session(rs), taskId);
    }

    @Override
    public Optional<Evaluation> findEvaluationBySubmission(UUID submissionId) {
        return evaluations("""
                SELECT id, submission_id, session_id, status, total_score, completed_at,
                       error_code, error_message
                  FROM m4_eval.evaluations
                 WHERE source_type = 'task_submission' AND submission_id = ?
                """, List.of(submissionId)).values().stream().findFirst();
    }

    @Override
    public Map<UUID, Evaluation> findEvaluationsBySubmissionIds(
            Collection<UUID> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(submissionIds);
        return evaluations("""
                SELECT id, submission_id, session_id, status, total_score, completed_at,
                       error_code, error_message
                  FROM m4_eval.evaluations
                 WHERE source_type = 'task_submission'
                   AND submission_id IN (%s)
                """.formatted(placeholders(ids.size())), ids);
    }

    private Map<UUID, Evaluation> evaluations(String sql, List<UUID> submissionIds) {
        List<EvaluationRow> rows = jdbc.query(sql, (rs, rowNum) -> new EvaluationRow(
                uuid(rs, "id"), uuid(rs, "submission_id"), uuid(rs, "session_id"),
                rs.getString("status"), rs.getBigDecimal("total_score"),
                instant(rs, "completed_at"), rs.getString("error_code"),
                rs.getString("error_message")), submissionIds.toArray());
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<UUID> evaluationIds = rows.stream().map(EvaluationRow::id).toList();
        Map<UUID, Map<String, BigDecimal>> dimensions = new HashMap<>();
        jdbc.query("""
                SELECT evaluation_id, dimension, score
                  FROM m4_eval.dimension_scores
                 WHERE evaluation_id IN (%s)
                """.formatted(placeholders(evaluationIds.size())),
                rs -> {
                    dimensions.computeIfAbsent(uuid(rs, "evaluation_id"),
                                    ignored -> new LinkedHashMap<>())
                            .put(rs.getString("dimension"), rs.getBigDecimal("score"));
                }, evaluationIds.toArray());
        Map<UUID, Evaluation> result = new LinkedHashMap<>();
        for (EvaluationRow row : rows) {
            result.put(row.submissionId(), new Evaluation(
                    row.id(), row.submissionId(), row.sessionId(), row.status(),
                    row.totalScore(), row.completedAt(),
                    dimensions.getOrDefault(row.id(), Map.of()),
                    row.errorCode(), row.errorMessage()));
        }
        return result;
    }

    @Override
    public Map<UUID, Account> findAccounts(Collection<UUID> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(accountIds);
        Map<UUID, Account> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT a.id, a.username, sp.class_id, a.school_id
                  FROM m1_auth.accounts a
                  LEFT JOIN m1_auth.student_profiles sp ON sp.account_id = a.id
                 WHERE a.id IN (%s)
                """.formatted(placeholders(ids.size())),
                rs -> {
                    UUID id = uuid(rs, "id");
                    result.put(id, new Account(id, rs.getString("username"),
                            uuid(rs, "class_id"), uuid(rs, "school_id")));
                }, ids.toArray());
        return result;
    }

    @Override
    public Map<UUID, String> findClassNames(Collection<UUID> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(classIds);
        Map<UUID, String> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, name FROM m1_auth.classes WHERE id IN (%s)
                """.formatted(placeholders(ids.size())),
                (RowCallbackHandler) rs -> result.put(uuid(rs, "id"), rs.getString("name")),
                ids.toArray());
        return result;
    }

    @Override
    public Map<UUID, String> findCourseStudentStatuses(
            UUID courseId, Collection<UUID> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = List.copyOf(studentIds);
        Map<UUID, String> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT student_account_id, status
                  FROM m2_course.course_students
                 WHERE course_id = ?
                   AND student_account_id IN (%s)
                 ORDER BY joined_at DESC
                """.formatted(placeholders(ids.size())),
                (RowCallbackHandler) rs -> result.putIfAbsent(uuid(rs, "student_account_id"),
                        rs.getString("status")),
                concat(courseId, ids));
        return result;
    }

    @Override
    public void lockTask(UUID taskId) {
        // Use the JPA EntityManager, not JdbcTemplate: only the EntityManager shares the
        // Hibernate/JPA transaction, so the row lock is held until commit. A JdbcTemplate
        // connection obtained inside a JpaTransactionManager would be a separate transaction
        // and would release the lock at the end of the statement.
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT id FROM m3_classroom.tasks WHERE id = :taskId FOR UPDATE
                        """)
                .setParameter("taskId", taskId)
                .getResultList();
        if (rows.isEmpty()) {
            throw new M7Exception(M7ErrorCode.RESOURCE_NOT_FOUND, "任务不存在",
                    Map.of("task_id", taskId));
        }
    }

    @Override
    public List<UUID> findRecentSubmissionIds(int limit) {
        return jdbc.query("""
                SELECT id FROM m3_classroom.submissions
                 ORDER BY submitted_at DESC
                 LIMIT ?
                """, (rs, rowNum) -> uuid(rs, "id"), limit);
    }

    private Task task(ResultSet rs) throws SQLException {
        Map<String, Integer> weights = jsonCodec.readWeights(rs.getString("rubric_weights"));
        if (weights.isEmpty()) {
            weights = jsonCodec.readWeightsFromSnapshot(rs.getString("config_snapshot"));
        }
        return new Task(uuid(rs, "id"), uuid(rs, "course_id"), rs.getString("mode"),
                rs.getString("topic"), rs.getString("status"), weights);
    }

    private static Submission submission(ResultSet rs) throws SQLException {
        return new Submission(uuid(rs, "id"), uuid(rs, "task_id"),
                uuid(rs, "student_account_id"), uuid(rs, "session_id"),
                uuid(rs, "class_id_snapshot"), instant(rs, "submitted_at"),
                rs.getString("status"));
    }

    private static Session session(ResultSet rs) throws SQLException {
        return new Session(uuid(rs, "id"), uuid(rs, "task_id"),
                uuid(rs, "student_account_id"), rs.getString("status"),
                instant(rs, "started_at"), instant(rs, "ended_at"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder(count * 2);
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        return builder.toString();
    }

    private static Object[] concat(UUID first, List<UUID> rest) {
        List<Object> values = new ArrayList<>(rest.size() + 1);
        values.add(first);
        values.addAll(rest);
        return values.toArray();
    }

    private record EvaluationRow(
            UUID id,
            UUID submissionId,
            UUID sessionId,
            String status,
            BigDecimal totalScore,
            Instant completedAt,
            String errorCode,
            String errorMessage) {
    }
}
