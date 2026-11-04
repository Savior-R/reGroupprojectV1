package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.TaskId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TaskPublishPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final UUID SCHOOL_ID = UUID.randomUUID();
    private static final UUID COURSE_ID = UUID.randomUUID();
    private static final UUID TEACHER_ID = UUID.randomUUID();

    private final TaskPublishPolicy policy = new TaskPublishPolicy();

    @Test
    void draftWithZeroStudentsIsRejected() {
        assertCode(() -> policy.validateForPublish(
                freeTask(), course(true, null), List.of(), List.of(), NOW),
                ErrorCode.TASK_NO_STUDENTS);
    }

    @Test
    void closedCourseIsRejected() {
        assertCode(() -> policy.validateForPublish(
                freeTask(), course(false, null), List.of(), students(1), NOW),
                ErrorCode.COURSE_ARCHIVED);
    }

    @Test
    void endedCourseIsRejected() {
        assertCode(() -> policy.validateForPublish(
                freeTask(), course(true, NOW.minusSeconds(1)), List.of(), students(1), NOW),
                ErrorCode.COURSE_ENDED);
    }

    @Test
    void publishedTaskIsRejected() {
        Task published = published(freeTask());
        assertCode(() -> policy.validateForPublish(
                published, course(true, null), List.of(), students(1), NOW),
                ErrorCode.TASK_ALREADY_PUBLISHED);
    }

    @Test
    void freeTaskWithoutMaterialIsRejected() {
        assertCode(() -> policy.validateForPublish(
                freeTaskWithoutMaterial(), course(true, null), List.of(), students(1), NOW),
                ErrorCode.REFERENCE_MATERIAL_MISSING);
    }

    @Test
    void freeTaskWithoutVirtualStudentsIsRejected() {
        assertCode(() -> policy.validateForPublish(
                freeTaskWithoutVirtualStudents(), course(true, null), List.of(), students(1), NOW),
                ErrorCode.TASK_CONFIG_INVALID);
    }

    @Test
    void weightSumNot100IsRejected() {
        assertCode(() -> policy.validateForPublish(
                freeTaskWithWeights(Map.of(
                        "content", 10, "expression", 10, "interaction", 10,
                        "board", 10, "time", 10)),
                course(true, null), List.of(), students(1), NOW),
                ErrorCode.WEIGHT_SUM_INVALID);
    }

    @Test
    void interviewQuestionCountIsOneToTen() {
        assertCode(() -> policy.validateForPublish(
                interviewTask(0), course(true, null), List.of(), students(1), NOW),
                ErrorCode.QUESTION_COUNT_INVALID);
    }

    @Test
    void duplicatedQuestionIdsRejectedAtConfigBoundary() {
        QuestionId id = new QuestionId(UUID.randomUUID());
        assertThatThrownBy(() -> new QuestionConfig(List.of(id, id), 300))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void disabledQuestionIsRejected() {
        Task task = interviewTask(1);
        QuestionId id = task.draft().questionConfig().questionIds().get(0);
        assertCode(() -> policy.validateForPublish(
                task, course(true, null),
                List.of(question(id, false, "答案要点", SCHOOL_ID)), students(1), NOW),
                ErrorCode.QUESTION_DISABLED);
    }

    @Test
    void missingAnswerPointsIsRejected() {
        Task task = interviewTask(1);
        QuestionId id = task.draft().questionConfig().questionIds().get(0);
        assertCode(() -> policy.validateForPublish(
                task, course(true, null),
                List.of(question(id, true, "  ", SCHOOL_ID)), students(1), NOW),
                ErrorCode.ANSWER_POINTS_MISSING);
    }

    @Test
    void crossSchoolQuestionIsRejected() {
        Task task = interviewTask(1);
        QuestionId id = task.draft().questionConfig().questionIds().get(0);
        assertCode(() -> policy.validateForPublish(
                task, course(true, null),
                List.of(question(id, true, "答案要点", UUID.randomUUID())), students(1), NOW),
                ErrorCode.CROSS_SCHOOL_ACCESS);
    }

    @Test
    void invalidFieldsAreAggregated() {
        Task task = freeTaskWithoutTextbookAndVirtualStudents();
        assertThatThrownBy(() -> policy.validateForPublish(
                task, course(true, null), List.of(), students(1), NOW))
                .isInstanceOfSatisfying(DomainException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.TASK_CONFIG_INVALID);
                    assertThat((List<?>) ex.details().get("fields")).hasSize(2);
                });
    }

    @Test
    void validFreeTaskPasses() {
        assertThatCode(() -> policy.validateForPublish(
                freeTask(), course(true, null), List.of(), students(1), NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void validInterviewTaskPasses() {
        Task task = interviewTask(1);
        QuestionId id = task.draft().questionConfig().questionIds().get(0);
        assertThatCode(() -> policy.validateForPublish(
                task, course(true, null),
                List.of(question(id, true, "答案要点", SCHOOL_ID)), students(1), NOW))
                .doesNotThrowAnyException();
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }

    private Task freeTask() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID), freeDraft(true, List.of(virtualStudent())), NOW);
        task.attachMaterial(material(), NOW);
        return task;
    }

    private Task freeTaskWithoutMaterial() {
        return Task.createDraft(
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID), freeDraft(true, List.of(virtualStudent())), NOW);
    }

    private Task freeTaskWithoutVirtualStudents() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID), freeDraft(true, List.of()), NOW);
        task.attachMaterial(material(), NOW);
        return task;
    }

    private Task freeTaskWithoutTextbookAndVirtualStudents() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID), freeDraft(false, List.of()), NOW);
        task.attachMaterial(material(), NOW);
        return task;
    }

    private Task freeTaskWithWeights(Map<String, Integer> weights) {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID),
                new TaskDraft(TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                        RubricWeights.of(TaskMode.FREE, weights), null,
                        List.of(virtualStudent()), null),
                NOW);
        task.attachMaterial(material(), NOW);
        return task;
    }

    private Task interviewTask(int questionCount) {
        List<QuestionId> ids = IntStream.range(0, questionCount)
                .mapToObj(i -> new QuestionId(UUID.randomUUID()))
                .toList();
        return Task.createDraft(
                new TaskId(UUID.randomUUID()), new CourseId(COURSE_ID),
                new AccountId(TEACHER_ID),
                new TaskDraft(TaskMode.INTERVIEW, "结构化面试", null, 300, 300,
                        RubricWeights.defaults(TaskMode.INTERVIEW), null, List.of(),
                        new QuestionConfig(ids, 300)),
                NOW);
    }

    private TaskDraft freeDraft(boolean withTextbook, List<VirtualStudentConfig> students) {
        return new TaskDraft(
                TaskMode.FREE, "荷塘月色", withTextbook ? "部编版" : null, 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null, students, null);
    }

    private Task published(Task task) {
        return Task.rehydrate(
                task.id(), task.courseId(), task.createdBy(), task.draft(),
                TaskStatus.PUBLISHED, task.referenceMaterial(), null, 1,
                NOW, null, task.createdAt(), task.updatedAt());
    }

    private CourseInfo course(boolean active, Instant endAt) {
        return new CourseInfo(COURSE_ID, SCHOOL_ID, "语文教学论", active, endAt,
                List.of(TEACHER_ID));
    }

    private List<CourseStudentInfo> students(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new CourseStudentInfo(UUID.randomUUID(), UUID.randomUUID()))
                .toList();
    }

    private PublishQuestion question(QuestionId id, boolean enabled, String answerPoints, UUID schoolId) {
        return new PublishQuestion(id.value(), "题干", answerPoints,
                UUID.randomUUID(), "教育理念", schoolId, enabled);
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
