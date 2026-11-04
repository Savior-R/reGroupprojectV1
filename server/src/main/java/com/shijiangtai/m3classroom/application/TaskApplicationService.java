package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.command.CreateTaskCommand;
import com.shijiangtai.m3classroom.application.command.StudentTaskQuery;
import com.shijiangtai.m3classroom.application.command.TaskQuery;
import com.shijiangtai.m3classroom.application.command.UpdateTaskCommand;
import com.shijiangtai.m3classroom.application.view.PublishResult;
import com.shijiangtai.m3classroom.application.view.StudentTaskDetailView;
import com.shijiangtai.m3classroom.application.view.StudentTaskView;
import com.shijiangtai.m3classroom.application.view.TaskAssignmentView;
import com.shijiangtai.m3classroom.application.view.TaskCardView;
import com.shijiangtai.m3classroom.application.view.TaskView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.PageResult;
import com.shijiangtai.shared.domain.TaskId;

public interface TaskApplicationService {
    TaskView createDraft(CurrentUser teacher, CreateTaskCommand command);

    TaskView get(CurrentUser teacher, TaskId taskId);

    TaskView updateDraft(CurrentUser teacher, TaskId taskId, UpdateTaskCommand command);

    void deleteDraft(CurrentUser teacher, TaskId taskId);

    PageResult<TaskCardView> listCourseTasks(
            CurrentUser teacher, CourseId courseId, TaskQuery query);

    PageResult<StudentTaskView> listStudentTasks(
            CurrentUser student, StudentTaskQuery query);

    StudentTaskDetailView getStudentTask(CurrentUser student, TaskId taskId);

    PageResult<TaskAssignmentView> listAssignments(
            CurrentUser teacher, TaskId taskId, int page, int size);
}
