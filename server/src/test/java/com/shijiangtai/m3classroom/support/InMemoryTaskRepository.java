package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public class InMemoryTaskRepository implements TaskRepository {
    private final Map<TaskId, Task> tasks = new ConcurrentHashMap<>();
    private final Map<TaskId, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public Task save(Task task) {
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<Task> findById(TaskId id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public List<Task> findByCourse(CourseId courseId) {
        return tasks.values().stream()
                .filter(task -> task.courseId().equals(courseId))
                .toList();
    }

    @Override
    public void delete(TaskId id) {
        tasks.remove(id);
    }

    @Override
    public <T> T withLock(TaskId id, Function<Task, T> action) {
        ReentrantLock lock = locks.computeIfAbsent(id, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Task task = tasks.get(id);
            if (task == null) {
                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在", Map.of("task_id", id.value()));
            }
            return action.apply(task);
        } finally {
            lock.unlock();
        }
    }
}
