package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.api.dto.CreateTaskRequest;
import com.shijiangtai.m3classroom.api.dto.TaskRequestMapper;
import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.command.MaterialUpload;
import com.shijiangtai.m3classroom.application.command.TaskQuery;
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
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
    private final TaskApplicationService taskApplicationService;
    private final TaskRequestMapper requestMapper;
    private final CurrentUserProvider currentUserProvider;

    public TaskController(
            TaskApplicationService taskApplicationService,
            TaskRequestMapper requestMapper,
            CurrentUserProvider currentUserProvider) {
        this.taskApplicationService = taskApplicationService;
        this.requestMapper = requestMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/courses/{courseId}/tasks")
    public TaskView create(
            @PathVariable UUID courseId, @RequestBody CreateTaskRequest request) {
        return taskApplicationService.create(
                currentUserProvider.require(), requestMapper.toCommand(courseId, request), null);
    }

    @PostMapping(
            value = "/courses/{courseId}/tasks",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TaskView createMultipart(
            @PathVariable UUID courseId,
            @RequestPart("config") CreateTaskRequest request,
            @RequestPart(value = "material", required = false) MultipartFile material) {
        return taskApplicationService.create(
                currentUserProvider.require(),
                requestMapper.toCommand(courseId, request),
                toUpload(material));
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
        return taskApplicationService.update(
                currentUserProvider.require(), new TaskId(taskId),
                requestMapper.toCommand(body), null);
    }

    @PatchMapping(
            value = "/tasks/{taskId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TaskView updateMultipart(
            @PathVariable UUID taskId,
            @RequestPart("config") JsonNode body,
            @RequestPart(value = "material", required = false) MultipartFile material) {
        return taskApplicationService.update(
                currentUserProvider.require(), new TaskId(taskId),
                requestMapper.toCommand(body), toUpload(material));
    }

    @PostMapping("/tasks/{taskId}/close")
    public TaskView close(@PathVariable UUID taskId) {
        CurrentUser user = currentUserProvider.require();
        TaskId id = new TaskId(taskId);
        taskApplicationService.close(user, id);
        return taskApplicationService.get(user, id);
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable UUID taskId) {
        taskApplicationService.softDelete(
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

    private MaterialUpload toUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return new MaterialUpload(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException exception) {
            throw new DomainException(ErrorCode.MATERIAL_PARSE_FAILED,
                    "参考材料读取失败");
        }
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
