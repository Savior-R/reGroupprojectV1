package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.domain.task.ReferenceMaterial;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.TaskId;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskMaterialTxService {
    private final TaskRepository taskRepository;
    private final ClockProvider clockProvider;

    public TaskMaterialTxService(TaskRepository taskRepository, ClockProvider clockProvider) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.clockProvider = Objects.requireNonNull(clockProvider, "clockProvider");
    }

    @Transactional
    public ReferenceMaterial attach(TaskId taskId, ReferenceMaterial material) {
        Task task = findTask(taskId);
        task.assertDraft();
        if (task.draft().mode() == TaskMode.INTERVIEW) {
            throw new DomainException(ErrorCode.MATERIAL_NOT_APPLICABLE,
                    "结构化面试不需要参考材料");
        }
        ReferenceMaterial old = task.referenceMaterial();
        task.attachMaterial(material, clockProvider.now());
        taskRepository.save(task);
        return old;
    }

    @Transactional
    public ReferenceMaterial clear(TaskId taskId) {
        Task task = findTask(taskId);
        task.assertDraft();
        ReferenceMaterial old = task.referenceMaterial();
        task.removeMaterial(clockProvider.now());
        taskRepository.save(task);
        return old;
    }

    private Task findTask(TaskId taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "任务不存在", Map.of("task_id", taskId.value())));
    }
}
