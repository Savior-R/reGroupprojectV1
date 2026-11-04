package com.shijiangtai.m3classroom.domain.task;

import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface TaskRepository {
    Task save(Task task);

    Optional<Task> findById(TaskId id);

    List<Task> findByCourse(CourseId courseId);

    void delete(TaskId id);

    <T> T withLock(TaskId id, Function<Task, T> action);
}
