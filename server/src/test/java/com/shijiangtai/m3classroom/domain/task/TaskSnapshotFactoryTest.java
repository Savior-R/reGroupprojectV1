package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskSnapshotFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID SCHOOL_ID = UUID.randomUUID();
    private static final UUID COURSE_ID = UUID.randomUUID();
    private static final UUID TEACHER_ID = UUID.randomUUID();

    private final TaskSnapshotFactory factory = new TaskSnapshotFactory();

    @Test
    void freeSnapshotContainsMaterialAndVirtualStudentsAndNoInterview() {
        Task task = freeTask();
        TaskSnapshot snapshot = factory.create(task, course(), List.of(), NOW);

        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.mode()).isEqualTo("free");
        assertThat(snapshot.referenceMaterial()).isNotNull();
        assertThat(snapshot.referenceMaterial().filename()).isEqualTo("荷塘月色.txt");
        assertThat(snapshot.virtualStudents()).hasSize(1);
        assertThat(snapshot.scenario()).isNull();
        assertThat(snapshot.interview()).isNull();
        assertThat(snapshot.publishedBy()).isEqualTo(task.createdBy().value());
    }

    @Test
    void scenarioSnapshotContainsScenarioAndNoInterview() {
        TaskSnapshot snapshot = factory.create(scenarioTask(), course(), List.of(), NOW);

        assertThat(snapshot.mode()).isEqualTo("scenario");
        assertThat(snapshot.scenario()).isNotNull();
        assertThat(snapshot.scenario().role()).isEqualTo("班主任");
        assertThat(snapshot.interview()).isNull();
    }

    @Test
    void interviewSnapshotContainsOrderedQuestionsAndNoMaterial() {
        Task task = interviewTask();
        PublishQuestion q1 = question("q1", "第一题");
        PublishQuestion q2 = question("q2", "第二题");

        TaskSnapshot snapshot = factory.create(task, course(), List.of(q2, q1), NOW);

        assertThat(snapshot.mode()).isEqualTo("interview");
        assertThat(snapshot.referenceMaterial()).isNull();
        assertThat(snapshot.virtualStudents()).isEmpty();
        assertThat(snapshot.interview()).isNotNull();
        assertThat(snapshot.interview().totalDurationSeconds()).isEqualTo(300);
        assertThat(snapshot.interview().questionCount()).isEqualTo(2);
        assertThat(snapshot.interview().questions()).extracting("order").containsExactly(1, 2);
        assertThat(snapshot.interview().questions())
                .extracting(TaskSnapshot.QuestionSnapshot::questionId)
                .containsExactly(q2.questionId(), q1.questionId());
    }

    @Test
    void weightsAreInModeDimensionOrder() {
        TaskSnapshot snapshot = factory.create(freeTask(), course(), List.of(), NOW);
        assertThat(snapshot.weights().keySet())
                .containsExactly("content", "expression", "interaction", "board", "time");
    }

    @Test
    void snapshotListsAreImmutable() {
        TaskSnapshot free = factory.create(freeTask(), course(), List.of(), NOW);
        assertThatThrownBy(() -> free.virtualStudents().add(free.virtualStudents().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);

        TaskSnapshot interview = factory.create(
                interviewTask(), course(), List.of(question("q1", "第一题")), NOW);
        assertThatThrownBy(() -> interview.interview().questions()
                .add(interview.interview().questions().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotDoesNotContainStudentRoster() {
        List<String> components = Arrays.stream(TaskSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(components).contains("virtualStudents");
        assertThat(components).doesNotContain("students", "roster", "assignments");
    }

    private Task freeTask() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()),
                new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID),
                new TaskDraft(TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                        RubricWeights.defaults(TaskMode.FREE), null,
                        List.of(virtualStudent()), null),
                NOW);
        task.attachMaterial(material(), NOW);
        return task;
    }

    private Task scenarioTask() {
        return Task.createDraft(
                new TaskId(UUID.randomUUID()),
                new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID),
                new TaskDraft(TaskMode.SCENARIO, "课堂管理", "教材", 900, 1200,
                        RubricWeights.defaults(TaskMode.SCENARIO),
                        new ScenarioConfig("班主任", "学生玩手机"), List.of(), null),
                NOW);
    }

    private Task interviewTask() {
        QuestionId q1 = new QuestionId(UUID.randomUUID());
        QuestionId q2 = new QuestionId(UUID.randomUUID());
        return Task.createDraft(
                new TaskId(UUID.randomUUID()),
                new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID),
                new TaskDraft(TaskMode.INTERVIEW, "结构化面试", null, 300, 300,
                        RubricWeights.defaults(TaskMode.INTERVIEW), null, List.of(),
                        new QuestionConfig(List.of(q1, q2), 300)),
                NOW);
    }

    private CourseInfo course() {
        return new CourseInfo(COURSE_ID, SCHOOL_ID, "语文教学论", true,
                Instant.parse("2027-06-30T00:00:00Z"), List.of(TEACHER_ID));
    }

    private PublishQuestion question(String id, String stem) {
        return new PublishQuestion(UUID.fromString("00000000-0000-0000-0000-" + id.replace("q", "00000000000")),
                stem, "答案要点", UUID.randomUUID(), "教育理念", SCHOOL_ID, true);
    }

    private VirtualStudentConfig virtualStudent() {
        return new VirtualStudentConfig(
                UUID.randomUUID(), "林小雨", "avatar-01", "性格外向",
                new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.50"), new BigDecimal("0.40"), 1, 1);
    }

    private ReferenceMaterial material() {
        return new ReferenceMaterial(
                UUID.randomUUID(), "荷塘月色.txt", "materials/key.txt", "text/plain",
                1024, "荷塘月色课文内容", 8);
    }
}
