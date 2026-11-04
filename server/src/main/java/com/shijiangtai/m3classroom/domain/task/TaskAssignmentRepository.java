package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Optional;

public interface TaskAssignmentRepository {
    void saveAll(List<TaskAssignment> assignments);

    List<TaskAssignment> findByTask(TaskId taskId);

    List<TaskAssignment> findByStudent(AccountId studentId);

    Optional<TaskAssignment> find(TaskId taskId, AccountId studentId);

    long countByTask(TaskId taskId);

    long countByTaskAndStatus(TaskId taskId, AssignmentStatus status);

    void deleteByTask(TaskId taskId);
}
