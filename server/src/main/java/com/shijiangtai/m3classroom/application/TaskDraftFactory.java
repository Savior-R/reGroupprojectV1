package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.Patch;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.command.VirtualStudentSpec;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.ScenarioConfig;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds domain task drafts from API commands. */
@Component
public class TaskDraftFactory {
    private final IdGenerator idGenerator;

    public TaskDraftFactory(IdGenerator idGenerator) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public TaskDraft fromCreate(CreateTaskCommand command) {
        Objects.requireNonNull(command, "command");
        TaskMode mode = command.mode();
        if (mode == null) {
            throw invalid("mode");
        }
        int recommended;
        int max;
        QuestionConfig questionConfig = command.questionConfig();
        if (mode == TaskMode.INTERVIEW) {
            int total;
            if (questionConfig != null) {
                total = questionConfig.totalDurationSeconds();
                if (command.durationRecommendedSeconds() != null
                        && !command.durationRecommendedSeconds().equals(total)) {
                    throw invalid("duration");
                }
                if (command.durationMaxSeconds() != null
                        && !command.durationMaxSeconds().equals(total)) {
                    throw invalid("duration");
                }
            } else {
                total = command.durationRecommendedSeconds() != null
                        ? command.durationRecommendedSeconds() : 300;
                questionConfig = new QuestionConfig(List.of(), total);
            }
            recommended = total;
            max = total;
        } else {
            recommended = command.durationRecommendedSeconds() != null
                    ? command.durationRecommendedSeconds() : mode.defaultRecommendedSeconds();
            max = command.durationMaxSeconds() != null
                    ? command.durationMaxSeconds() : mode.defaultMaxSeconds();
            if (questionConfig != null) {
                throw invalid("question_config");
            }
        }
        Map<String, Integer> weights = command.rubricWeights() != null
                ? command.rubricWeights() : mode.defaultWeights().asMap();
        return new TaskDraft(
                mode, command.topic(), command.textbookVersion(), recommended, max,
                RubricWeights.of(mode, weights), command.scenario(),
                toConfigs(command.virtualStudents()), questionConfig);
    }

    public TaskDraft fromUpdate(TaskDraft base, UpdateTaskCommand command) {
        TaskMode mode = command.mode().present() ? command.mode().value() : base.mode();
        if (mode == null) {
            throw invalid("mode");
        }
        boolean modeChanged = mode != base.mode();
        String topic = command.topic().present() ? command.topic().value() : base.topic();
        String textbook = command.textbookVersion().present()
                ? command.textbookVersion().value() : base.textbookVersion();

        if (modeChanged) {
            int recommended = command.durationRecommendedSeconds().present()
                    ? requireInt(command.durationRecommendedSeconds(), "duration")
                    : mode.defaultRecommendedSeconds();
            int max = command.durationMaxSeconds().present()
                    ? requireInt(command.durationMaxSeconds(), "duration")
                    : mode.defaultMaxSeconds();
            QuestionConfig questionConfig = null;
            if (mode == TaskMode.INTERVIEW) {
                int total = command.durationRecommendedSeconds().present()
                        ? recommended : 300;
                if (command.durationMaxSeconds().present() && max != total) {
                    throw invalid("duration");
                }
                questionConfig = new QuestionConfig(List.of(), total);
                recommended = total;
                max = total;
            }
            return new TaskDraft(mode, topic, textbook, recommended, max,
                    mode.defaultWeights(), null, List.of(), questionConfig);
        }

        int recommended = command.durationRecommendedSeconds().present()
                ? requireInt(command.durationRecommendedSeconds(), "duration")
                : base.durationRecommendedSeconds();
        int max = command.durationMaxSeconds().present()
                ? requireInt(command.durationMaxSeconds(), "duration")
                : base.durationMaxSeconds();
        Map<String, Integer> weights = command.rubricWeights().present()
                ? command.rubricWeights().value() : base.rubricWeights().asMap();
        ScenarioConfig scenario = command.scenario().present()
                ? command.scenario().value() : base.scenario();
        List<VirtualStudentConfig> students = command.virtualStudents().present()
                ? toConfigs(command.virtualStudents().value()) : base.virtualStudents();
        QuestionConfig questionConfig = command.questionConfig().present()
                ? command.questionConfig().value() : base.questionConfig();

        if (mode == TaskMode.INTERVIEW && command.questionConfig().present()) {
            if (questionConfig == null) {
                questionConfig = new QuestionConfig(
                        List.of(), base.durationRecommendedSeconds());
            }
            int total = questionConfig.totalDurationSeconds();
            if (command.durationRecommendedSeconds().present() && recommended != total) {
                throw invalid("duration");
            }
            if (command.durationMaxSeconds().present() && max != total) {
                throw invalid("duration");
            }
            recommended = total;
            max = total;
        }

        return new TaskDraft(mode, topic, textbook, recommended, max,
                RubricWeights.of(mode, weights), scenario, students, questionConfig);
    }

    public List<VirtualStudentConfig> toConfigs(List<VirtualStudentSpec> specs) {
        if (specs == null) {
            return List.of();
        }
        List<VirtualStudentConfig> result = new ArrayList<>();
        for (VirtualStudentSpec spec : specs) {
            if (spec == null) {
                throw invalid("virtual_students");
            }
            UUID id = spec.virtualStudentId() != null
                    ? spec.virtualStudentId() : idGenerator.nextId();
            result.add(new VirtualStudentConfig(
                    id, spec.name(), spec.avatar(), spec.personalityText(),
                    spec.activity(), spec.questionRate(), spec.ability(),
                    spec.attentionBaseline(), spec.disciplineProbability(),
                    spec.seatNo(), spec.sortOrder()));
        }
        return List.copyOf(result);
    }

    private int requireInt(Patch<Integer> patch, String field) {
        if (!patch.present() || patch.value() == null) {
            throw invalid(field);
        }
        return patch.value();
    }

    private static DomainException invalid(String field) {
        return new DomainException(ErrorCode.TASK_CONFIG_INVALID,
                "任务配置非法", Map.of("field", field));
    }
}
