package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class TaskAssignmentRepositoryAdapter implements TaskAssignmentRepository {
    private final TaskAssignmentJpaRepository repository;

    public TaskAssignmentRepositoryAdapter(TaskAssignmentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void saveAll(List<TaskAssignment> assignments) {
        repository.saveAll(assignments.stream().map(this::toEntity).toList());
    }

    @Override
    public List<TaskAssignment> findByTask(TaskId taskId) {
        return repository.findByTaskId(taskId.value()).stream().map(this::toDomain).toList();
    }

    @Override
    public List<TaskAssignment> findByStudent(AccountId studentId) {
        return repository.findByStudentAccountId(studentId.value()).stream()
                .map(this::toDomain).toList();
    }

    @Override
    public Optional<TaskAssignment> find(TaskId taskId, AccountId studentId) {
        return repository.findByTaskIdAndStudentAccountId(taskId.value(), studentId.value())
                .map(this::toDomain);
    }

    @Override
    public long countByTask(TaskId taskId) {
        return repository.countByTaskId(taskId.value());
    }

    @Override
    public long countByTaskAndStatus(TaskId taskId, AssignmentStatus status) {
        return repository.countByTaskIdAndStatus(taskId.value(), status.wireValue());
    }

    @Override
    public void deleteByTask(TaskId taskId) {
        repository.deleteByTaskId(taskId.value());
    }

    private TaskAssignmentEntity toEntity(TaskAssignment assignment) {
        TaskAssignmentEntity entity = new TaskAssignmentEntity();
        entity.setId(assignment.id());
        entity.setTaskId(assignment.taskId().value());
        entity.setStudentAccountId(assignment.studentAccountId().value());
        entity.setClassIdSnapshot(assignment.classIdSnapshot() == null
                ? null : assignment.classIdSnapshot().value());
        entity.setAssignedAt(assignment.assignedAt());
        entity.setStatus(assignment.status().wireValue());
        return entity;
    }

    private TaskAssignment toDomain(TaskAssignmentEntity entity) {
        return new TaskAssignment(
                entity.getId(),
                new TaskId(entity.getTaskId()),
                new AccountId(entity.getStudentAccountId()),
                entity.getClassIdSnapshot() == null
                        ? null : new ClassId(entity.getClassIdSnapshot()),
                entity.getAssignedAt(),
                AssignmentStatus.fromWire(entity.getStatus()));
    }
}
