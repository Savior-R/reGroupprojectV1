package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.application.port.SessionEventStore;
import com.shijiangtai.m3classroom.domain.session.SessionEvent;
import com.shijiangtai.shared.domain.SessionId;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable append-only event log backed by {@code m3_classroom.events}.
 *
 * <p>{@link #append} inserts with {@code ON CONFLICT (session_id, seq) DO NOTHING}, so replaying a
 * command cannot duplicate or renumber events. Reads are ordered by {@code seq} and bounded by
 * {@code limit}.
 */
@Repository
public class SessionEventStoreAdapter implements SessionEventStore {
    private static final String INSERT_SQL = """
            INSERT INTO m3_classroom.events
                (id, session_id, seq, type, timestamp_ms, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (session_id, seq) DO NOTHING
            """;

    private final SessionEventJpaRepository repository;
    private final SessionJsonCodec jsonCodec;
    private final JdbcTemplate jdbcTemplate;

    public SessionEventStoreAdapter(
            SessionEventJpaRepository repository,
            SessionJsonCodec jsonCodec,
            JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jsonCodec = jsonCodec;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(SessionEvent event) {
        String payload = jsonCodec.writeEventPayload(event.payload());
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setObject(1, event.eventId());
                statement.setObject(2, event.sessionId().value());
                statement.setLong(3, event.seq());
                statement.setString(4, event.type());
                statement.setLong(5, event.atMs());
                statement.setString(6, payload);
                statement.setObject(7,
                        OffsetDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC));
            }

            @Override
            public int getBatchSize() {
                return 1;
            }
        });
    }

    @Override
    public List<SessionEvent> after(SessionId id, long seq, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return repository.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(
                        id.value(), seq, PageRequest.of(0, limit)).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long maxSeq(SessionId id) {
        return repository.maxSeq(id.value());
    }

    private SessionEvent toDomain(SessionEventEntity entity) {
        return new SessionEvent(
                entity.getId(),
                new SessionId(entity.getSessionId()),
                entity.getSeq(),
                entity.getType(),
                entity.getTimestampMs(),
                jsonCodec.readEventPayload(entity.getPayload()),
                entity.getCreatedAt());
    }
}
