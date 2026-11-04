package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskDraftTest {

    @Test
    void interviewRequiresQuestionConfig() {
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.INTERVIEW, "topic", null, 300, 300,
                RubricWeights.defaults(TaskMode.INTERVIEW), null, List.of(), null))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.TASK_CONFIG_INVALID));
    }

    @Test
    void interviewDurationsMustEqualTotal() {
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.INTERVIEW, "topic", null, 300, 301,
                RubricWeights.defaults(TaskMode.INTERVIEW), null, List.of(),
                new QuestionConfig(List.of(), 300)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void interviewCannotCarryVirtualStudents() {
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.INTERVIEW, "topic", null, 300, 300,
                RubricWeights.defaults(TaskMode.INTERVIEW), null,
                List.of(virtualStudent()), new QuestionConfig(List.of(), 300)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void freeCannotCarryQuestionConfig() {
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.FREE, "topic", null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(),
                new QuestionConfig(List.of(), 300)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void topicLengthIsOneToHundred() {
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.FREE, " ", null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.FREE, "x".repeat(101), null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null))
                .isInstanceOf(DomainException.class);
        assertThat(new TaskDraft(
                TaskMode.FREE, "x", null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null).topic())
                .isEqualTo("x");
        assertThat(new TaskDraft(
                TaskMode.FREE, "x".repeat(100), null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null).topic())
                .hasSize(100);
    }

    @Test
    void recommendedDurationMayNotExceedMax() {
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.FREE, "topic", null, 1200, 900,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.FREE, "topic", null, 0, 900,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void draftWeightsNeedNotSumTo100() {
        RubricWeights weights = RubricWeights.of(TaskMode.FREE, Map.of(
                "content", 10, "expression", 10, "interaction", 10, "board", 10, "time", 10));
        TaskDraft draft = new TaskDraft(
                TaskMode.FREE, "topic", null, 900, 1200, weights, null, List.of(), null);
        assertThat(draft.rubricWeights().sum()).isEqualTo(50);
    }

    @Test
    void scenarioOnlyForScenarioMode() {
        ScenarioConfig scenario = new ScenarioConfig("班主任", "学生玩手机");
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.FREE, "topic", null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), scenario, List.of(), null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.INTERVIEW, "topic", null, 300, 300,
                RubricWeights.defaults(TaskMode.INTERVIEW), scenario, List.of(),
                new QuestionConfig(List.of(), 300)))
                .isInstanceOf(DomainException.class);
        assertThat(new TaskDraft(
                TaskMode.SCENARIO, "topic", "教材", 900, 1200,
                RubricWeights.defaults(TaskMode.SCENARIO), scenario, List.of(), null)
                .scenario()).isEqualTo(scenario);
    }

    @Test
    void nullVirtualStudentsAreNormalizedToEmpty() {
        TaskDraft draft = new TaskDraft(
                TaskMode.FREE, "topic", null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, null, null);
        assertThat(draft.virtualStudents()).isEmpty();
    }

    @Test
    void blankTextbookVersionIsRejected() {
        assertThatThrownBy(() -> new TaskDraft(
                TaskMode.FREE, "topic", "   ", 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, List.of(), null))
                .isInstanceOf(DomainException.class);
    }

    private VirtualStudentConfig virtualStudent() {
        return new VirtualStudentConfig(
                UUID.randomUUID(), "林小雨", "avatar-01", "性格外向",
                new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.50"), new BigDecimal("0.40"), 1, 1);
    }
}
