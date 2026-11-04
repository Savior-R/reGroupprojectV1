package com.shijiangtai.m3classroom.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.RubricWeights;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshot;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.m3classroom.infrastructure.config.DevDataConfig;
import com.shijiangtai.m3classroom.infrastructure.persistence.jpa.TaskJsonCodec;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.TaskId;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class TaskRepositoryIT extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskAssignmentRepository assignmentRepository;

    @Autowired
    private TaskSnapshotFactory snapshotFactory;

    @Autowired
    private TaskJsonCodec jsonCodec;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndReadsTaskWithJsonb() {
        Task task = freeTask();
        taskRepository.save(task);

        Task loaded = taskRepository.findById(task.id()).orElseThrow();

        assertThat(loaded.draft().topic()).isEqualTo("荷塘月色");
        assertThat(loaded.draft().virtualStudents()).hasSize(1);
        assertThat(loaded.referenceMaterial()).isNotNull();
        assertThat(loaded.status().wireValue()).isEqualTo("published");
        assertThat(loaded.snapshot()).isNotNull();

        String configSnapshot = jdbc.queryForObject(
                "select config_snapshot::text from m3_classroom.tasks where id = ?",
                String.class, task.id().value());
        assertThat(jsonCodec.readTaskSnapshot(configSnapshot).schemaVersion()).isEqualTo(1);
    }

    @Test
    void replacesVirtualStudentsOnUpdate() {
        Task task = draftFreeTask();
        taskRepository.save(task);

        Task loaded = taskRepository.findById(task.id()).orElseThrow();
        TaskDraft replacement = new TaskDraft(
                TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                RubricWeights.defaults(TaskMode.FREE), null,
                List.of(virtualStudent(), virtualStudent()), null);
        loaded.updateDraft(replacement, NOW.plusSeconds(10));
        taskRepository.save(loaded);
        entityManager.flush();

        Integer count = jdbc.queryForObject(
                "select count(*) from m3_classroom.virtual_students where task_id = ?",
                Integer.class, task.id().value());
        assertThat(count).isEqualTo(2);
    }

    @Test
    void deleteCascadesAssignmentsAndVirtualStudents() {
        Task task = freeTask();
        taskRepository.save(task);
        assignmentRepository.saveAll(List.of(new TaskAssignment(
                UUID.randomUUID(), task.id(), new AccountId(DevDataConfig.STUDENT_ID),
                new ClassId(DevDataConfig.CLASS_ID), NOW, AssignmentStatus.ASSIGNED)));

        taskRepository.delete(task.id());

        assertThat(count("select count(*) from m3_classroom.tasks")).isZero();
        assertThat(count("select count(*) from m3_classroom.task_assignments")).isZero();
        assertThat(count("select count(*) from m3_classroom.virtual_students")).isZero();
    }

    private Task freeTask() {
        Task task = draftFreeTask();
        TaskSnapshot snapshot = snapshotFactory.create(task, course(), List.of(), NOW);
        task.publish(snapshot, NOW);
        return task;
    }

    private Task draftFreeTask() {
        Task task = Task.createDraft(
                new TaskId(UUID.randomUUID()),
                new CourseId(DevDataConfig.COURSE_ID),
                new AccountId(DevDataConfig.TEACHER_ID),
                new TaskDraft(TaskMode.FREE, "荷塘月色", "部编版", 900, 1200,
                        RubricWeights.defaults(TaskMode.FREE), null,
                        List.of(virtualStudent()), null),
                NOW);
        task.attachMaterial(new ReferenceMaterial(
                UUID.randomUUID(), "荷塘月色.txt", "materials/key.txt", "text/plain",
                1024, "荷塘月色课文内容", 8), NOW);
        return task;
    }

    private CourseInfo course() {
        return new CourseInfo(
                DevDataConfig.COURSE_ID, DevDataConfig.SCHOOL_ID, "语文教学论",
                true, Instant.parse("2027-06-30T00:00:00Z"),
                List.of(DevDataConfig.TEACHER_ID));
    }

    private VirtualStudentConfig virtualStudent() {
        return new VirtualStudentConfig(
                UUID.randomUUID(), "林小雨", "avatar-01", "性格外向",
                new BigDecimal("0.60"), new BigDecimal("0.50"), new BigDecimal("0.70"),
                new BigDecimal("0.50"), new BigDecimal("0.40"), 1, 1);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
