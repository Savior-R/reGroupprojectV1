package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskDraft;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.m3classroom.domain.task.VirtualStudentConfig;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepositoryAdapter implements TaskRepository {
    private final TaskJpaRepository taskRepository;
    private final VirtualStudentJpaRepository virtualStudentRepository;
    private final TaskJsonCodec jsonCodec;

    public TaskRepositoryAdapter(
            TaskJpaRepository taskRepository,
            VirtualStudentJpaRepository virtualStudentRepository,
            TaskJsonCodec jsonCodec) {
        this.taskRepository = taskRepository;
        this.virtualStudentRepository = virtualStudentRepository;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Task save(Task task) {
        taskRepository.save(toEntity(task));
        virtualStudentRepository.deleteByTaskId(task.id().value());
        List<VirtualStudentEntity> students = task.draft().virtualStudents().stream()
                .map(student -> toEntity(task.id().value(), student, task.updatedAt()))
                .toList();
        virtualStudentRepository.saveAll(students);
        return task;
    }

    @Override
    public Optional<Task> findById(TaskId id) {
        return taskRepository.findById(id.value()).map(this::toDomain);
    }

    @Override
    public List<Task> findByCourse(CourseId courseId) {
        return taskRepository.findByCourseIdOrderByUpdatedAtDesc(courseId.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void delete(TaskId id) {
        taskRepository.deleteById(id.value());
    }

    @Override
    public <T> T withLock(TaskId id, Function<Task, T> action) {
        TaskEntity entity = taskRepository.findByIdForUpdate(id.value())
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在", Map.of("task_id", id.value())));
        return action.apply(toDomain(entity));
    }

    private TaskEntity toEntity(Task task) {
        TaskEntity entity = new TaskEntity();
        entity.setId(task.id().value());
        entity.setCourseId(task.courseId().value());
        entity.setMode(task.draft().mode().wireValue());
        entity.setTopic(task.draft().topic());
        entity.setTextbookVersion(task.draft().textbookVersion());
        entity.setReferenceMaterial(task.referenceMaterial() == null
                ? null : jsonCodec.writeReferenceMaterial(task.referenceMaterial()));
        entity.setScenarioConfig(task.draft().scenario() == null
                ? null : jsonCodec.writeScenarioConfig(task.draft().scenario()));
        entity.setDurationRecommendedSeconds(task.draft().durationRecommendedSeconds());
        entity.setDurationMaxSeconds(task.draft().durationMaxSeconds());
        entity.setRubricWeights(jsonCodec.writeRubricWeights(task.draft().rubricWeights()));
        entity.setQuestionConfig(task.draft().questionConfig() == null
                ? null : jsonCodec.writeQuestionConfig(task.draft().questionConfig()));
        entity.setConfigSnapshot(task.snapshot() == null
                ? null : jsonCodec.writeTaskSnapshot(task.snapshot()));
        entity.setConfigVersion(task.configVersion());
        entity.setStatus(task.status().wireValue());
        entity.setCreatedBy(task.createdBy().value());
        entity.setPublishedAt(task.publishedAt());
        entity.setClosedAt(task.closedAt());
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(task.updatedAt());
        return entity;
    }

    private VirtualStudentEntity toEntity(
            java.util.UUID taskId, VirtualStudentConfig student, java.time.Instant createdAt) {
        VirtualStudentEntity entity = new VirtualStudentEntity();
        entity.setId(student.id());
        entity.setTaskId(taskId);
        entity.setName(student.name());
        entity.setAvatar(student.avatar());
        entity.setPersonalityText(student.personalityText());
        entity.setActivity(student.activity());
        entity.setQuestionRate(student.questionRate());
        entity.setAbility(student.ability());
        entity.setAttentionBaseline(student.attentionBaseline());
        entity.setDisciplineProbability(student.disciplineProbability());
        entity.setSeatNo(student.seatNo());
        entity.setSortOrder(student.sortOrder());
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private Task toDomain(TaskEntity entity) {
        List<VirtualStudentConfig> students = virtualStudentRepository
                .findByTaskId(entity.getId()).stream()
                .map(this::toConfig)
                .toList();
        TaskDraft draft = new TaskDraft(
                TaskMode.fromWire(entity.getMode()),
                entity.getTopic(),
                entity.getTextbookVersion(),
                entity.getDurationRecommendedSeconds(),
                entity.getDurationMaxSeconds(),
                jsonCodec.readRubricWeights(entity.getRubricWeights()),
                entity.getScenarioConfig() == null
                        ? null : jsonCodec.readScenarioConfig(entity.getScenarioConfig()),
                students,
                entity.getQuestionConfig() == null
                        ? null : jsonCodec.readQuestionConfig(entity.getQuestionConfig()));
        return Task.rehydrate(
                new TaskId(entity.getId()),
                new CourseId(entity.getCourseId()),
                new AccountId(entity.getCreatedBy()),
                draft,
                TaskStatus.fromWire(entity.getStatus()),
                entity.getReferenceMaterial() == null
                        ? null : jsonCodec.readReferenceMaterial(entity.getReferenceMaterial()),
                entity.getConfigSnapshot() == null
                        ? null : jsonCodec.readTaskSnapshot(entity.getConfigSnapshot()),
                entity.getConfigVersion(),
                entity.getPublishedAt(),
                entity.getClosedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private VirtualStudentConfig toConfig(VirtualStudentEntity entity) {
        return new VirtualStudentConfig(
                entity.getId(),
                entity.getName(),
                entity.getAvatar(),
                entity.getPersonalityText(),
                entity.getActivity(),
                entity.getQuestionRate(),
                entity.getAbility(),
                entity.getAttentionBaseline(),
                entity.getDisciplineProbability(),
                entity.getSeatNo(),
                entity.getSortOrder());
    }
}
