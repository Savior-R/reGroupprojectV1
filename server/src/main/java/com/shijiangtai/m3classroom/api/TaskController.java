package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.api.dto.CreateTaskRequest;
import com.shijiangtai.m3classroom.api.dto.TaskRequestMapper;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.TaskPublishService;
import com.shijiangtai.m3classroom.application.command.TaskQuery;
import com.shijiangtai.m3classroom.application.view.PublishResult;
import com.shijiangtai.m3classroom.application.view.TaskAssignmentView;
import com.shijiangtai.m3classroom.application.view.TaskCardView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.m3classroom.domain.task.TaskMode;
import com.shijiangtai.m3classroom.domain.task.TaskStatus;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.TaskId;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
    private final TaskApplicationService taskApplicationService;
    private final TaskPublishService taskPublishService;
    private final TaskRequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public TaskController(
            TaskApplicationService taskApplicationService,
            TaskPublishService taskPublishService,
            TaskRequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.taskApplicationService = taskApplicationService;
        this.taskPublishService = taskPublishService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/courses/{courseId}/tasks")
    public TaskView create(
            @PathVariable UUID courseId, @RequestBody CreateTaskRequest request) {
        return taskApplicationService.createDraft(
                currentUserProvider.require(), requestMapper.toCommand(courseId, request));
    }

    @GetMapping("/courses/{courseId}/tasks")
    public PageResult<TaskCardView> list(
            @PathVariable UUID courseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode) {
        return taskApplicationService.listCourseTasks(
                currentUserProvider.require(),
                new CourseId(courseId),
                new TaskQuery(page, size, parseStatus(status), parseMode(mode)));
    }

    @GetMapping("/tasks/{taskId}")
    public Object get(@PathVariable UUID taskId) {
        CurrentUser user = currentUserProvider.require();
        TaskId id = new TaskId(taskId);
        return user.type() == AccountType.STUDENT
                ? taskApplicationService.getStudentTask(user, id)
                : taskApplicationService.get(user, id);
    }

    @PatchMapping("/tasks/{taskId}")
    public TaskView update(@PathVariable UUID taskId, @RequestBody JsonNode body) {
        return taskApplicationService.updateDraft(
                currentUserProvider.require(),
                new TaskId(taskId),
                requestMapper.toCommand(body));
    }

    @PostMapping("/tasks/{taskId}/publish")
    public PublishResult publish(@PathVariable UUID taskId) {
        return taskPublishService.publish(
                currentUserProvider.require(), new TaskId(taskId));
    }

    @PostMapping("/tasks/{taskId}/close")
    public TaskView close(@PathVariable UUID taskId) {
        CurrentUser user = currentUserProvider.require();
        TaskId id = new TaskId(taskId);
        taskPublishService.close(user, id);
        return taskApplicationService.get(user, id);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable UUID taskId) {
        taskApplicationService.deleteDraft(
                currentUserProvider.require(), new TaskId(taskId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks/{taskId}/assignments")
    public PageResult<TaskAssignmentView> assignments(
            @PathVariable UUID taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskApplicationService.listAssignments(
                currentUserProvider.require(), new TaskId(taskId), page, size);
    }

    private TaskStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.fromWire(value);
        } catch (DomainException exception) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "非法任务状态");
        }
    }

    private TaskMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TaskMode.fromWire(value);
        } catch (DomainException exception) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "非法任务模式");
        }
    }
}
