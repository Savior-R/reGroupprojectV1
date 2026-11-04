package com.shijiangtai.m3classroom.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.api.dto.CreateTaskRequest;
import com.shijiangtai.m3classroom.api.dto.TaskRequestMapper;
import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class TaskRequestMapperTest {
    private final JsonMapper objectMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    private final TaskRequestMapper mapper = new TaskRequestMapper(objectMapper);

    @Test
    void mapsSnakeCaseCreateRequest() {
        String json = """
                {
                  "mode": "free",
                  "topic": "荷塘月色",
                  "textbook_version": "部编版",
                  "rubric_weights": {"content": 30, "expression": 20, "interaction": 20, "board": 15, "time": 15},
                  "virtual_students": [{
                    "name": "林小雨", "avatar": "avatar-01", "personality_text": "性格外向",
                    "activity": 0.60, "question_rate": 0.50, "ability": 0.70,
                    "attention_baseline": 0.50, "discipline_probability": 0.40,
                    "seat_no": 1, "sort_order": 1
                  }]
                }
                """;

        CreateTaskRequest request = objectMapper.readValue(json, CreateTaskRequest.class);
        CreateTaskCommand command = mapper.toCommand(UUID.randomUUID(), request);

        assertThat(command.mode()).isEqualTo(TaskMode.FREE);
        assertThat(command.topic()).isEqualTo("荷塘月色");
        assertThat(command.rubricWeights()).containsEntry("content", 30);
        assertThat(command.virtualStudents()).hasSize(1);
        assertThat(command.virtualStudents().get(0).activity())
                .isEqualByComparingTo(new BigDecimal("0.60"));
    }

    @Test
    void updateMapperDistinguishesMissingFromExplicitNull() {
        JsonNode body = objectMapper.readTree(
                "{\"topic\":\"新课题\",\"textbook_version\":null}");

        UpdateTaskCommand command = mapper.toCommand(body);

        assertThat(command.topic().present()).isTrue();
        assertThat(command.topic().value()).isEqualTo("新课题");
        assertThat(command.textbookVersion().present()).isTrue();
        assertThat(command.textbookVersion().value()).isNull();
        assertThat(command.mode().present()).isFalse();
    }

    @Test
    void mapsInterviewQuestionIds() {
        String json = """
                {
                  "mode": "interview",
                  "topic": "结构化面试",
                  "question_config": {
                    "question_ids": ["00000000-0000-0000-0000-000000000301"],
                    "total_duration_seconds": 300
                  }
                }
                """;

        CreateTaskRequest request = objectMapper.readValue(json, CreateTaskRequest.class);
        CreateTaskCommand command = mapper.toCommand(UUID.randomUUID(), request);

        assertThat(command.questionConfig()).isNotNull();
        assertThat(command.questionConfig().questionIds()).hasSize(1);
        assertThat(command.questionConfig().totalDurationSeconds()).isEqualTo(300);
    }
}
