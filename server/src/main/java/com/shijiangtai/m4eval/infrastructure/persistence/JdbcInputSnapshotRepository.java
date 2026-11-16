package com.shijiangtai.m4eval.infrastructure.persistence;

import com.shijiangtai.m4eval.application.port.EvaluationInputSnapshotRepository;
import com.shijiangtai.m4eval.domain.InputSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInputSnapshotRepository implements EvaluationInputSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final M4JsonCodec json;

    public JdbcInputSnapshotRepository(NamedParameterJdbcTemplate jdbc, M4JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public InputSnapshot save(InputSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO m4_eval.input_snapshots (
                    evaluation_id, source_type, source_id, submission_id, training_record_id,
                    recording_ids, text_ids, frame_ids, event_ids, config_version,
                    task_config_version, training_config_version, flags, created_at)
                VALUES (
                    :evaluationId, :sourceType, :sourceId, :submissionId, :trainingRecordId,
                    CAST(:recordingIds AS jsonb), CAST(:textIds AS jsonb),
                    CAST(:frameIds AS jsonb), CAST(:eventIds AS jsonb), :configVersion,
                    :taskConfigVersion, :trainingConfigVersion, CAST(:flags AS jsonb),
                    :createdAt)
                ON CONFLICT (evaluation_id) DO UPDATE SET
                    recording_ids = EXCLUDED.recording_ids,
                    text_ids = EXCLUDED.text_ids,
                    frame_ids = EXCLUDED.frame_ids,
                    event_ids = EXCLUDED.event_ids,
                    flags = EXCLUDED.flags
                """, new MapSqlParameterSource()
                .addValue("evaluationId", snapshot.evaluationId())
                .addValue("sourceType", snapshot.sourceType())
                .addValue("sourceId", snapshot.sourceId())
                .addValue("submissionId", snapshot.submissionId())
                .addValue("trainingRecordId", snapshot.trainingRecordId())
                .addValue("recordingIds", json.write(strings(snapshot.recordingIds())))
                .addValue("textIds", json.write(strings(snapshot.textIds())))
                .addValue("frameIds", json.write(strings(snapshot.frameIds())))
                .addValue("eventIds", json.write(strings(snapshot.eventIds())))
                .addValue("configVersion", snapshot.configVersion())
                .addValue("taskConfigVersion", snapshot.taskConfigVersion())
                .addValue("trainingConfigVersion", snapshot.trainingConfigVersion())
                .addValue("flags", json.write(snapshot.flags()))
                .addValue("createdAt", M4JdbcAccess.timestamp(snapshot.createdAt())));
        return snapshot;
    }

    @Override
    public Optional<InputSnapshot> findByEvaluationId(UUID evaluationId) {
        return jdbc.query("""
                SELECT evaluation_id, source_type, source_id, submission_id, training_record_id,
                       recording_ids, text_ids, frame_ids, event_ids, config_version,
                       task_config_version, training_config_version, flags, created_at
                FROM m4_eval.input_snapshots
                WHERE evaluation_id = :evaluationId
                """, Map.of("evaluationId", evaluationId), this::mapRow)
                .stream().findFirst();
    }

    private InputSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new InputSnapshot(
                M4JdbcAccess.uuid(rs, "evaluation_id"),
                rs.getString("source_type"),
                M4JdbcAccess.uuid(rs, "source_id"),
                M4JdbcAccess.uuid(rs, "submission_id"),
                M4JdbcAccess.uuid(rs, "training_record_id"),
                uuids(json.readStringList(rs.getString("recording_ids"))),
                uuids(json.readStringList(rs.getString("text_ids"))),
                uuids(json.readStringList(rs.getString("frame_ids"))),
                uuids(json.readStringList(rs.getString("event_ids"))),
                rs.getInt("config_version"),
                M4JdbcAccess.nullableInt(rs, "task_config_version"),
                M4JdbcAccess.nullableInt(rs, "training_config_version"),
                json.readMap(rs.getString("flags")),
                M4JdbcAccess.instant(rs, "created_at"));
    }

    private static List<String> strings(List<UUID> ids) {
        List<String> values = new ArrayList<>();
        for (UUID id : ids) {
            values.add(id.toString());
        }
        return values;
    }

    private static List<UUID> uuids(List<String> values) {
        List<UUID> ids = new ArrayList<>();
        for (String value : values) {
            ids.add(UUID.fromString(value));
        }
        return List.copyOf(ids);
    }
}
