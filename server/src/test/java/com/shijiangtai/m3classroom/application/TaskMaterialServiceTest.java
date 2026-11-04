package com.shijiangtai.m3classroom.application;

import static com.shijiangtai.m3classroom.TestFixtures.COURSE;
import static com.shijiangtai.m3classroom.TestFixtures.NOW;
import static com.shijiangtai.m3classroom.TestFixtures.otherTeacher;
import static com.shijiangtai.m3classroom.TestFixtures.student;
import static com.shijiangtai.m3classroom.TestFixtures.teacher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.application.view.MaterialView;
import com.shijiangtai.m3classroom.domain.task.QuestionConfig;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.infrastructure.material.CompositeMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.DocxMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.PdfMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.PlainTextMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.storage.InMemoryMaterialObjectStorage;
import com.shijiangtai.m3classroom.support.FakeCourseQueryPort;
import com.shijiangtai.m3classroom.support.InMemoryTaskRepository;
import com.shijiangtai.m3classroom.support.PdfTestFiles;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskMaterialServiceTest {

    private static final String VALID_TEXT =
            "这是一份足够长的参考材料文本内容，用于测试材料上传。";
    private static final String VALID_TEXT_2 =
            "这是第二份足够长的参考材料文本内容，用于测试材料替换。";

    private InMemoryTaskRepository tasks;
    private FakeCourseQueryPort courses;
    private InMemoryMaterialObjectStorage storage;
    private TaskMaterialService service;

    @BeforeEach
    void setUp() {
        tasks = new InMemoryTaskRepository();
        courses = com.shijiangtai.m3classroom.TestFixtures.coursePort();
        storage = new InMemoryMaterialObjectStorage();
        TaskAccessPolicy access = new TaskAccessPolicy(courses);
        TaskMaterialTxService tx = new TaskMaterialTxService(tasks, () -> NOW);
        MaterialTextExtractor extractor = new CompositeMaterialTextExtractor(
                List.of(new PdfMaterialTextExtractor(), new DocxMaterialTextExtractor(),
                        new PlainTextMaterialTextExtractor()));
        service = new TaskMaterialServiceImpl(
                tasks, courses, access, extractor, storage, tx, UUID::randomUUID);
    }

    @Test
    void uploadStoresObjectAndAttachesMaterial() {
        TaskId taskId = createFreeTask();

        MaterialView view = service.upload(
                teacher(), taskId, "a.txt", "text/plain", bytes(VALID_TEXT));

        assertThat(view.parseStatus()).isEqualTo("parsed");
        assertThat(view.preview()).isEqualTo(VALID_TEXT);
        assertThat(view.parsedText()).isNull();
        Task task = tasks.findById(taskId).orElseThrow();
        assertThat(task.referenceMaterial()).isNotNull();
        assertThat(task.referenceMaterial().objectKey()).startsWith("materials/")
                .endsWith(".txt");
        assertThat(storage.keys()).contains(task.referenceMaterial().objectKey());
    }

    @Test
    void uploadRejectsInterviewMode() {
        TaskId taskId = createInterviewTask();
        assertCode(() -> service.upload(
                teacher(), taskId, "a.txt", "text/plain", bytes(VALID_TEXT)),
                ErrorCode.MATERIAL_NOT_APPLICABLE);
    }

    @Test
    void uploadRejectsOversizeAndUnsupportedType() {
        TaskId taskId = createFreeTask();
        assertCode(() -> service.upload(
                teacher(), taskId, "a.txt", "text/plain", new byte[20 * 1024 * 1024 + 1]),
                ErrorCode.MATERIAL_TOO_LARGE);
        assertCode(() -> service.upload(
                teacher(), taskId, "a.exe", "application/octet-stream", bytes(VALID_TEXT)),
                ErrorCode.MATERIAL_TYPE_UNSUPPORTED);
    }

    @Test
    void parseFailureKeepsOldMaterialAndOldObject() {
        TaskId taskId = createFreeTask();
        service.upload(teacher(), taskId, "a.txt", "text/plain", bytes(VALID_TEXT));
        Task before = tasks.findById(taskId).orElseThrow();
        String oldKey = before.referenceMaterial().objectKey();

        assertCode(() -> service.upload(
                teacher(), taskId, "blank.pdf", "application/pdf", PdfTestFiles.blankPdf()),
                ErrorCode.MATERIAL_PARSE_FAILED);

        Task after = tasks.findById(taskId).orElseThrow();
        assertThat(after.referenceMaterial().objectKey()).isEqualTo(oldKey);
        assertThat(storage.get(oldKey)).isNotNull();
    }

    @Test
    void replacementDeletesOldObjectAfterAttach() {
        TaskId taskId = createFreeTask();
        service.upload(teacher(), taskId, "a.txt", "text/plain", bytes(VALID_TEXT));
        String oldKey = tasks.findById(taskId).orElseThrow().referenceMaterial().objectKey();

        service.upload(teacher(), taskId, "b.txt", "text/plain", bytes(VALID_TEXT_2));

        Task task = tasks.findById(taskId).orElseThrow();
        assertThat(task.referenceMaterial().objectKey()).isNotEqualTo(oldKey);
        assertThat(storage.get(oldKey)).isNull();
        assertThat(storage.get(task.referenceMaterial().objectKey())).isNotNull();
    }

    @Test
    void deleteRemovesTaskMaterialAndObject() {
        TaskId taskId = createFreeTask();
        service.upload(teacher(), taskId, "a.txt", "text/plain", bytes(VALID_TEXT));
        String key = tasks.findById(taskId).orElseThrow().referenceMaterial().objectKey();

        service.delete(teacher(), taskId);

        assertThat(tasks.findById(taskId).orElseThrow().referenceMaterial()).isNull();
        assertThat(storage.get(key)).isNull();
    }

    @Test
    void getReturnsFullParsedTextOnlyForTeacherGroup() {
        TaskId taskId = createFreeTask();
        service.upload(teacher(), taskId, "a.txt", "text/plain", bytes(VALID_TEXT));

        MaterialView view = service.get(teacher(), taskId);
        assertThat(view.parsedText()).isEqualTo(VALID_TEXT);
        assertCode(() -> service.get(otherTeacher(), taskId), ErrorCode.TEACHER_NOT_IN_GROUP);
        assertCode(() -> service.get(student(), taskId), ErrorCode.FORBIDDEN);
    }

    private TaskId createFreeTask() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        Task task = Task.createDraft(
                taskId, COURSE, com.shijiangtai.m3classroom.TestFixtures.TEACHER,
                new TaskDraft(TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                        RubricWeights.defaults(TaskMode.FREE), null, List.of(), null),
                NOW);
        tasks.save(task);
        return taskId;
    }

    private TaskId createInterviewTask() {
        TaskId taskId = new TaskId(UUID.randomUUID());
        Task task = Task.createDraft(
                taskId, COURSE, com.shijiangtai.m3classroom.TestFixtures.TEACHER,
                new TaskDraft(TaskMode.INTERVIEW, "结构化面试", null, 300, 300,
                        RubricWeights.defaults(TaskMode.INTERVIEW), null, List.of(),
                        new QuestionConfig(List.of(), 300)),
                NOW);
        tasks.save(task);
        return taskId;
    }

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.code()).isEqualTo(expected));
    }
}
