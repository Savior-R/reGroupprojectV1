package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.TaskJsonCodec;
import com.shijiangtai.shared.domain.QuestionId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TaskJsonCodecIT extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Autowired
    private TaskJsonCodec codec;

    @Test
    void snapshotJsonUsesSnakeCaseAndRoundTrips() {
        UUID questionId = UUID.randomUUID();
        TaskSnapshot snapshot = new TaskSnapshot(
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "interview",
                "结构化面试",
                null,
                new TaskSnapshot.DurationSnapshot(300, 300),
                new LinkedHashMap<>(Map.of("content", 40, "expression", 30, "time", 30)),
                new TaskSnapshot.MaterialSnapshot(
                        UUID.randomUUID(), "a.txt", "materials/key.txt", "text/plain",
                        1024, "材料内容", 4),
                new TaskSnapshot.ScenarioSnapshot("班主任", "学生玩手机"),
                List.of(),
                new TaskSnapshot.InterviewSnapshot(
                        300,
                        1,
                        List.of(new TaskSnapshot.QuestionSnapshot(
                                questionId, 1, "题干", "答案要点",
                                UUID.randomUUID(), "教育理念"))),
                NOW,
                UUID.randomUUID());

        String json = codec.writeTaskSnapshot(snapshot);

        assertThat(json).contains("\"schema_version\"", "\"published_at\"", "\"question_count\"");
        assertThat(codec.readTaskSnapshot(json)).isEqualTo(snapshot);
    }

    @Test
    void questionConfigRoundTripsWithQuestionIds() {
        QuestionConfig config = new QuestionConfig(
                List.of(new QuestionId(UUID.randomUUID()), new QuestionId(UUID.randomUUID())),
                300);
        assertThat(codec.readQuestionConfig(codec.writeQuestionConfig(config)))
                .isEqualTo(config);
    }

    @Test
    void referenceMaterialRoundTrips() {
        ReferenceMaterial material = new ReferenceMaterial(
                UUID.randomUUID(), "荷塘月色.txt", "materials/key.txt", "text/plain",
                1024, "荷塘月色课文内容", 8);
        assertThat(codec.readReferenceMaterial(codec.writeReferenceMaterial(material)))
                .isEqualTo(material);
    }
}
