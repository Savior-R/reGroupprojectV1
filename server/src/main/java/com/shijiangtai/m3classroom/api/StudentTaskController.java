package com.shijiangtai.m3classroom.api;

import com.shijiangtai.m3classroom.application.TaskApplicationService;
import com.shijiangtai.m3classroom.application.command.StudentTaskQuery;
import com.shijiangtai.m3classroom.application.view.StudentTaskDetailView;
import com.shijiangtai.m3classroom.application.view.StudentTaskView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.TaskId;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/student/tasks")
public class StudentTaskController {
    private final TaskApplicationService taskApplicationService;
    private final CurrentUserProvider currentUserProvider;

    public StudentTaskController(
            TaskApplicationService taskApplicationService,
            CurrentUserProvider currentUserProvider) {
        this.taskApplicationService = taskApplicationService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public PageResult<StudentTaskView> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskApplicationService.listStudentTasks(
                currentUserProvider.require(), new StudentTaskQuery(page, size));
    }

    @GetMapping("/{taskId}")
    public StudentTaskDetailView get(@PathVariable UUID taskId) {
        return taskApplicationService.getStudentTask(
                currentUserProvider.require(), new TaskId(taskId));
    }
}
