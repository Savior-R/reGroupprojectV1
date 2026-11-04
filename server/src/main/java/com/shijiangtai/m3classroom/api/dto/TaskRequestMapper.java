package com.shijiangtai.m3classroom.api.dto;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.Patch;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.command.VirtualStudentSpec;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.QuestionId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TaskRequestMapper {
    private final ObjectMapper objectMapper;

    public TaskRequestMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public CreateTaskCommand toCommand(UUID courseId, CreateTaskRequest request) {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(request, "request");
        return new CreateTaskCommand(
                new CourseId(courseId),
                TaskMode.fromWire(request.mode()),
                request.topic(),
                request.textbookVersion(),
                request.durationRecommendedSeconds(),
                request.durationMaxSeconds(),
                request.rubricWeights(),
                request.scenario() == null
                        ? null : new ScenarioConfig(
                                request.scenario().role(), request.scenario().situation()),
                request.virtualStudents() == null
                        ? List.of()
                        : request.virtualStudents().stream().map(this::toSpec).toList(),
                request.questionConfig() == null
                        ? null : toQuestionConfig(request.questionConfig()));
    }

    public UpdateTaskCommand toCommand(JsonNode body) {
        Objects.requireNonNull(body, "body");
        return new UpdateTaskCommand(
                patch(body, "mode", node -> TaskMode.fromWire(node.asText())),
                patch(body, "topic", JsonNode::asText),
                patch(body, "textbook_version", JsonNode::asText),
                patch(body, "duration_recommended_seconds", JsonNode::asInt),
                patch(body, "duration_max_seconds", JsonNode::asInt),
                patch(body, "rubric_weights", this::toWeights),
                patch(body, "scenario", this::toScenario),
                patch(body, "virtual_students", this::toSpecs),
                patch(body, "question_config", this::toQuestionConfig));
    }

    private <T> Patch<T> patch(JsonNode body, String field, Function<JsonNode, T> mapper) {
        if (!body.has(field)) {
            return Patch.absent();
        }
        JsonNode node = body.get(field);
        return Patch.of(node == null || node.isNull() ? null : mapper.apply(node));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> toWeights(JsonNode node) {
        return objectMapper.treeToValue(node, Map.class);
    }

    private ScenarioConfig toScenario(JsonNode node) {
        ScenarioRequest request = objectMapper.treeToValue(node, ScenarioRequest.class);
        return new ScenarioConfig(request.role(), request.situation());
    }

    private List<VirtualStudentSpec> toSpecs(JsonNode node) {
        VirtualStudentRequest[] requests =
                objectMapper.treeToValue(node, VirtualStudentRequest[].class);
        return Arrays.stream(requests).map(this::toSpec).toList();
    }

    private VirtualStudentSpec toSpec(VirtualStudentRequest request) {
        return new VirtualStudentSpec(
                request.virtualStudentId(),
                request.name(),
                request.avatar(),
                request.personalityText(),
                request.activity(),
                request.questionRate(),
                request.ability(),
                request.attentionBaseline(),
                request.disciplineProbability(),
                request.seatNo(),
                request.sortOrder());
    }

    private QuestionConfig toQuestionConfig(JsonNode node) {
        return toQuestionConfig(objectMapper.treeToValue(node, QuestionConfigRequest.class));
    }

    private QuestionConfig toQuestionConfig(QuestionConfigRequest request) {
        List<QuestionId> questionIds = request.questionIds() == null
                ? List.of()
                : request.questionIds().stream().map(QuestionId::new).toList();
        return new QuestionConfig(questionIds, request.totalDurationSeconds());
    }
}
