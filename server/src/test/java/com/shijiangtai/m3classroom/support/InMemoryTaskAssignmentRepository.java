package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.domain.task.AssignmentStatus;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.m3classroom.domain.task.TaskAssignmentRepository;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryTaskAssignmentRepository implements TaskAssignmentRepository {
    private final Map<UUID, TaskAssignment> assignments = new ConcurrentHashMap<>();

    @Override
    public void saveAll(List<TaskAssignment> items) {
        items.forEach(assignment -> assignments.put(assignment.id(), assignment));
    }

    @Override
    public List<TaskAssignment> findByTask(TaskId taskId) {
        return assignments.values().stream()
                .filter(assignment -> assignment.taskId().equals(taskId))
                .toList();
    }

    @Override
    public List<TaskAssignment> findByStudent(AccountId studentId) {
        return assignments.values().stream()
                .filter(assignment -> assignment.studentAccountId().equals(studentId))
                .toList();
    }

    @Override
    public Optional<TaskAssignment> find(TaskId taskId, AccountId studentId) {
        return assignments.values().stream()
                .filter(assignment -> assignment.taskId().equals(taskId))
                .filter(assignment -> assignment.studentAccountId().equals(studentId))
                .findFirst();
    }

    @Override
    public long countByTask(TaskId taskId) {
        return findByTask(taskId).size();
    }

    @Override
    public long countByTaskAndStatus(TaskId taskId, AssignmentStatus status) {
        return assignments.values().stream()
                .filter(assignment -> assignment.taskId().equals(taskId))
                .filter(assignment -> assignment.status() == status)
                .count();
    }

    @Override
    public void deleteByTask(TaskId taskId) {
        assignments.values().removeIf(assignment -> assignment.taskId().equals(taskId));
    }
}
